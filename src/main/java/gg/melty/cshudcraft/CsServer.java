package gg.melty.cshudcraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server side (also the integrated server in single player): CS state per player, the authority on
 * shots, damage, reloads, purchases, grenades, money and XP. The client only asks; this decides.
 */
public final class CsServer implements ModInitializer {
    public static final Identifier SPEED_ID = Identifier.of(CsHudCraft.MOD_ID, "weapon_speed");
    public static final RegistryKey<EntityType<?>> GRENADE_KEY = RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(CsHudCraft.MOD_ID, "grenade"));
    public static EntityType<GrenadeEntity> GRENADE;
    public static final RegistryKey<EntityType<?>> C4_KEY = RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(CsHudCraft.MOD_ID, "c4"));
    public static EntityType<C4Entity> C4;

    /** CS units per block, for range falloff (a 72-unit CS player is 1.8 blocks). */
    static final double UNITS_PER_BLOCK = 40.0;

    private static final class Live {
        long nextAttack;
        long reloadStarted;
        String reloadWeapon = "";
        boolean aliveSinceDusk;
        long plantStart;
        int defuseId = -1;
        long defuseStart;
    }

    record LastHit(UUID attacker, String weapon, boolean headshot, long at) {}

    private static final Map<UUID, Live> LIVE = new HashMap<>();
    private static final Map<UUID, LastHit> LAST_HIT = new HashMap<>();
    private static final List<GrenadeEntity.Smoke> SMOKES = new ArrayList<>();
    private static long lastTimeOfDay = -1;
    /** Set while our own bullet hits a player's head, so armor knows whether the helmet counts. */
    public static final ThreadLocal<Boolean> HEAD_HIT = ThreadLocal.withInitial(() -> false);

    @Override
    public void onInitialize() {
        Sheets.load();
        CsNet.register();
        GRENADE = Registry.register(Registries.ENTITY_TYPE, GRENADE_KEY,
                EntityType.Builder.<GrenadeEntity>create(GrenadeEntity::new, SpawnGroup.MISC)
                        .dimensions(0.25f, 0.25f).maxTrackingRange(4).trackingTickInterval(2).build(GRENADE_KEY));
        C4 = Registry.register(Registries.ENTITY_TYPE, C4_KEY,
                EntityType.Builder.<C4Entity>create(C4Entity::new, SpawnGroup.MISC)
                        .dimensions(0.6f, 0.3f).maxTrackingRange(8).trackingTickInterval(5).build(C4_KEY));

        ServerPlayNetworking.registerGlobalReceiver(CsNet.Action.ID, (payload, ctx) -> handle(ctx.player(), payload));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ensure(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive) return;
            CsState s = state(newPlayer);
            // CS: you keep your money; a fresh life starts with knife and the default pistol
            s.primary = "";
            s.grenades.clear();
            s.hasBomb = false;
            s.armor = 0;
            s.helmet = false;
            s.giveStartLoadout();
            save(newPlayer, s);
            live(newPlayer).aliveSinceDusk = false;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register(CsServer::onDeath);
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, st, be) -> {
            if (player instanceof ServerPlayerEntity sp && isPrecious(st)) award(sp, "ore_mined", 0);
        });
        ServerTickEvents.END_SERVER_TICK.register(CsServer::tick);
        registerTestCommands();
    }

    /**
     * Testing helpers for single-player worlds only: /csmoney <amount> sets your money,
     * /cslevel <level> sets your level (unlocks weapons). Not available on multiplayer servers.
     */
    private static void registerTestCommands() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
            dispatcher.register(net.minecraft.server.command.CommandManager.literal("csmoney")
                    .requires(src -> src.getServer() != null && src.getServer().isSingleplayer())
                    .then(net.minecraft.server.command.CommandManager.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 1_000_000))
                            .executes(ctx -> {
                                ServerPlayerEntity p = ctx.getSource().getPlayer();
                                if (p == null) return 0;
                                CsState s = state(p);
                                s.money = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount");
                                save(p, s);
                                int m = s.money;
                                ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal("CS money: $" + m), false);
                                return 1;
                            })));
            dispatcher.register(net.minecraft.server.command.CommandManager.literal("cslevel")
                    .requires(src -> src.getServer() != null && src.getServer().isSingleplayer())
                    .then(net.minecraft.server.command.CommandManager.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, (int) Sheets.eco("maxLevel")))
                            .executes(ctx -> {
                                ServerPlayerEntity p = ctx.getSource().getPlayer();
                                if (p == null) return 0;
                                CsState s = state(p);
                                s.level = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level");
                                s.xp = 0;
                                save(p, s);
                                int l = s.level;
                                ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal("CS level: " + l), false);
                                return 1;
                            })));
        });
    }

    // ---------------------------------------------------------------- state

    public static CsState state(PlayerEntity p) {
        CsState s = p.getAttached(CsNet.STATE);
        if (s == null || !s.initialized) {
            s = CsState.fresh();
            p.setAttached(CsNet.STATE, s);
        }
        return s;
    }

    static void ensure(ServerPlayerEntity p) { save(p, state(p)); }

    /** Every change goes through here so the owner gets it synced. */
    static void save(ServerPlayerEntity p, CsState s) { p.setAttached(CsNet.STATE, s.copy()); }

    private static Live live(PlayerEntity p) { return LIVE.computeIfAbsent(p.getUuid(), u -> new Live()); }

    static void send(ServerPlayerEntity p, String kind, String arg, float... nums) {
        List<Float> l = new ArrayList<>(nums.length);
        for (float f : nums) l.add(f);
        ServerPlayNetworking.send(p, new CsNet.Event(kind, arg, l));
    }

    /** A CS sound at a place, for every player nearby except the one who made it (they play it themselves). */
    static void soundAround(ServerWorld w, Vec3d pos, String sound, float volume, PlayerEntity except, double radius) {
        soundAround(w, pos, sound, volume, except, radius, null);
    }

    /** channel: a new sound on the same channel cuts the last one (GoldSrc entity channels). */
    static void soundAround(ServerWorld w, Vec3d pos, String sound, float volume, PlayerEntity except, double radius, String channel) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p == except || p.squaredDistanceTo(pos) > radius * radius) continue;
            if (channel == null) send(p, "sound", sound, (float) pos.x, (float) pos.y, (float) pos.z, volume);
            else send(p, "sound_ch", channel + "|" + sound, (float) pos.x, (float) pos.y, (float) pos.z, volume);
        }
    }

    // ---------------------------------------------------------------- actions

    static void handle(ServerPlayerEntity p, CsNet.Action a) {
        CsState s = state(p);
        Live live = live(p);
        long now = System.currentTimeMillis();
        switch (a.action()) {
            case "mode" -> { s.csMode = a.arg().equals("cs"); save(p, s); }
            case "select" -> {
                int slot = a.nums().isEmpty() ? s.slot : Math.round(a.nums().get(0));
                if (s.hasSlot(slot)) {
                    s.slot = slot;
                    if (slot == CsState.SLOT_GRENADE && !a.arg().isEmpty() && s.grenades.getOrDefault(a.arg(), 0) > 0) s.grenade = a.arg();
                    live.reloadWeapon = "";
                    save(p, s);
                }
            }
            case "fire" -> fire(p, s, live, a, now);
            case "knife" -> knife(p, s, live, a, now);
            case "reload_start" -> {
                Sheets.Weapon w = s.current();
                if (w != null && w.id().equals(a.arg()) && !w.isKnife() && s.clip(w.id()) < w.clip() && s.reserveOf(w.ammo()) > 0) {
                    live.reloadStarted = now;
                    live.reloadWeapon = w.id();
                }
            }
            case "reload_finish" -> {
                Sheets.Weapon w = s.current();
                if (w == null || !w.id().equals(a.arg()) || !live.reloadWeapon.equals(w.id())) return;
                if (now - live.reloadStarted < (w.shellReload() ? 200 : 400)) return;
                int room = w.clip() - s.clip(w.id());
                int n = Math.min(w.shellReload() ? 1 : room, s.reserveOf(w.ammo()));
                if (n <= 0) return;
                s.clips.put(w.id(), s.clip(w.id()) + n);
                s.reserve.put(w.ammo(), s.reserveOf(w.ammo()) - n);
                live.reloadStarted = now;
                if (!w.shellReload()) live.reloadWeapon = "";
                save(p, s);
            }
            case "throw" -> throwGrenade(p, s, a);
            case "plant_start" -> {
                if (s.hasBomb && s.slot == CsState.SLOT_BOMB) live.plantStart = now;
            }
            case "plant" -> plant(p, s, live, now);
            case "defuse_start" -> {
                C4Entity bomb = bombNear(p, a);
                if (bomb != null) {
                    live.defuseId = bomb.getId();
                    live.defuseStart = now;
                    soundAround(p.getEntityWorld(), bomb.getEntityPos(), "weapons/c4_disarm.wav", 1f, null, 32);
                }
            }
            case "defuse_finish" -> {
                C4Entity bomb = bombNear(p, a);
                if (bomb == null || bomb.getId() != live.defuseId || now - live.defuseStart < 9500) return;
                live.defuseId = -1;
                ServerWorld w = p.getEntityWorld();
                soundAround(w, bomb.getEntityPos(), "weapons/c4_disarmed.wav", 1f, null, 48);
                bomb.discard();
                for (ServerPlayerEntity o : w.getPlayers()) send(o, "sound", "radio/bombdef.wav");
                award(p, "bomb_defused", 0);
            }
            case "buy" -> buy(p, s, a.arg());
            default -> { }
        }
    }

    // ---------------------------------------------------------------- shooting

    private static void fire(ServerPlayerEntity p, CsState s, Live live, CsNet.Action a, long now) {
        Sheets.Weapon w = s.current();
        if (w == null || w.isKnife() || !w.id().equals(a.arg()) || !s.csMode) return;
        if (s.clip(w.id()) <= 0) return;
        if (now + 40 < live.nextAttack) return; // faster than the weapon can cycle
        live.nextAttack = now + (long) (w.cycle() * 1000);
        live.reloadWeapon = "";
        s.clips.put(w.id(), s.clip(w.id()) - 1);
        save(p, s);

        ServerWorld world = p.getEntityWorld();
        Vec3d eye = p.getEyePos();
        soundAround(world, eye, w.fireSound(), 1f, p, 64);
        List<Float> d = a.nums();
        int pellets = Math.min(w.pellets(), d.size() / 3);
        for (int i = 0; i < pellets; i++) {
            Vec3d dir = new Vec3d(d.get(i * 3), d.get(i * 3 + 1), d.get(i * 3 + 2));
            if (dir.lengthSquared() < 1e-6) continue;
            bullet(p, world, eye, dir.normalize(), w);
        }
    }

    private static void bullet(ServerPlayerEntity p, ServerWorld world, Vec3d eye, Vec3d dir, Sheets.Weapon w) {
        Vec3d end = eye.add(dir.multiply(w.rangeBlocks()));
        BlockHitResult block = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        Vec3d stop = block.getType() == HitResult.Type.MISS ? end : block.getPos();
        EntityHitResult ent = ProjectileUtil.raycast(p, eye, stop, new Box(eye, stop).expand(1.0),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e != p, eye.squaredDistanceTo(stop));
        if (ent != null) {
            LivingEntity target = (LivingEntity) ent.getEntity();
            Vec3d hit = ent.getPos();
            double dist = eye.distanceTo(hit);
            float cs = (float) (w.damage() * Math.pow(w.rangeModifier(), dist * UNITS_PER_BLOCK / 500.0));
            boolean head = isHead(target, hit);
            hurt(p, world, target, cs * (head ? (float) Sheets.eco("headshotMul") : 1f), w.id(), head);
            world.spawnParticles(new DustParticleEffect(0x8A0303, 1.2f), hit.x, hit.y, hit.z, 6, 0.08, 0.08, 0.08, 0.0);
        } else if (block.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = block.getBlockPos();
            BlockState st = world.getBlockState(pos);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, st), stop.x, stop.y, stop.z, 5, 0.05, 0.05, 0.05, 0.05);
            world.spawnParticles(ParticleTypes.SMOKE, stop.x, stop.y, stop.z, 2, 0.02, 0.02, 0.02, 0.01);
            double dist = eye.distanceTo(stop);
            BlockDamage.damage(p, world, pos, (float) (w.damage() * Math.pow(w.rangeModifier(), dist * UNITS_PER_BLOCK / 500.0)));
        }
    }

    /** The top quarter of a body above its eyes' level minus a little is the head (CS hitgroup HEAD). */
    static boolean isHead(LivingEntity t, Vec3d hit) {
        double eyeY = t.getEyeY();
        double headBottom = eyeY - Math.max(0.2, t.getHeight() * 0.12);
        return hit.y >= headBottom;
    }

    static boolean isShatterable(BlockState st) {
        return st.isIn(BlockTags.IMPERMEABLE) || st.isOf(Blocks.GLASS_PANE) || st.getBlock().getTranslationKey().endsWith("stained_glass_pane");
    }

    /** CS damage applied to a Minecraft entity, with kill credit, no invulnerability frames between bullets. */
    static void hurt(ServerPlayerEntity p, ServerWorld world, LivingEntity target, float csDamage, String weapon, boolean head) {
        LAST_HIT.put(target.getUuid(), new LastHit(p.getUuid(), weapon, head, System.currentTimeMillis()));
        target.timeUntilRegen = 0;
        HEAD_HIT.set(head);
        try {
            target.damage(world, world.getDamageSources().playerAttack(p), csDamage * (float) Sheets.eco("damageScale"));
        } finally {
            HEAD_HIT.set(false);
        }
        target.timeUntilRegen = 0;
        send(p, "hit", head ? "head" : "body", head ? 1f : 0f);
    }

    private static void knife(ServerPlayerEntity p, CsState s, Live live, CsNet.Action a, long now) {
        if (!s.csMode || s.slot != CsState.SLOT_KNIFE) return;
        boolean stab = a.arg().equals("stab");
        if (now + 40 < live.nextAttack) return;
        live.nextAttack = now + (stab ? 1000 : 350);
        Vec3d dir = a.nums().size() >= 3 ? new Vec3d(a.nums().get(0), a.nums().get(1), a.nums().get(2)).normalize() : p.getRotationVec(1f);
        Vec3d eye = p.getEyePos();
        double range = stab ? 2.0 : 2.5;
        Vec3d end = eye.add(dir.multiply(range));
        ServerWorld world = p.getEntityWorld();
        BlockHitResult block = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        Vec3d stop = block.getType() == HitResult.Type.MISS ? end : block.getPos();
        EntityHitResult ent = ProjectileUtil.raycast(p, eye, stop, new Box(eye, stop).expand(0.6),
                e -> e instanceof LivingEntity && e.isAlive() && e != p, eye.squaredDistanceTo(stop));
        if (ent == null) {
            if (block.getType() == HitResult.Type.BLOCK) BlockDamage.damage(p, world, block.getBlockPos(), stab ? 65f : 15f);
            return;
        }
        LivingEntity t = (LivingEntity) ent.getEntity();
        float dmg = stab ? 65f : 15f;
        Vec3d toTarget = t.getEntityPos().subtract(p.getEntityPos()).multiply(1, 0, 1).normalize();
        Vec3d facing = t.getRotationVec(1f).multiply(1, 0, 1).normalize();
        if (stab && toTarget.dotProduct(facing) > 0.5) dmg *= 3; // backstab
        hurt(p, world, t, dmg, "knife", false);
        soundAround(world, t.getEyePos(), stab ? "weapons/knife_stab.wav" : "weapons/knife_hit1.wav", 1f, p, 32);
    }

    // ---------------------------------------------------------------- C4

    /** CS plants take the full 3 s of the pressbutton animation, on the ground. */
    private static void plant(ServerPlayerEntity p, CsState s, Live live, long now) {
        if (!s.hasBomb || s.slot != CsState.SLOT_BOMB || !s.csMode || !p.isOnGround()) return;
        if (live.plantStart == 0 || now - live.plantStart < 2800) return;
        live.plantStart = 0;
        ServerWorld w = p.getEntityWorld();
        C4Entity bomb = new C4Entity(C4, w);
        bomb.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), p.getYaw(), 0);
        bomb.planter = p.getUuid();
        w.spawnEntity(bomb);
        s.hasBomb = false;
        s.slot = s.hasSlot(CsState.SLOT_PRIMARY) ? CsState.SLOT_PRIMARY : s.hasSlot(CsState.SLOT_PISTOL) ? CsState.SLOT_PISTOL : CsState.SLOT_KNIFE;
        save(p, s);
        soundAround(w, bomb.getEntityPos(), "weapons/c4_plant.wav", 1f, null, 48);
        for (ServerPlayerEntity o : w.getPlayers()) send(o, "sound", "radio/bombpl.wav");
        award(p, "bomb_planted", 0);
    }

    /** The planted C4 a defuse request names (entity id in nums[0]), if the player is close enough. */
    private static C4Entity bombNear(ServerPlayerEntity p, CsNet.Action a) {
        if (a.nums().isEmpty()) return null;
        Entity e = p.getEntityWorld().getEntityById(Math.round(a.nums().get(0)));
        if (!(e instanceof C4Entity bomb) || !bomb.isAlive()) return null;
        return p.getEyePos().distanceTo(bomb.getEntityPos()) <= 3.2 ? bomb : null;
    }

    // ---------------------------------------------------------------- grenades

    private static void throwGrenade(ServerPlayerEntity p, CsState s, CsNet.Action a) {
        String id = a.arg();
        Sheets.Equipment g = Sheets.EQUIPMENT.get(id);
        if (g == null || !g.isGrenade() || s.grenades.getOrDefault(id, 0) <= 0 || !s.csMode) return;
        s.grenades.put(id, s.grenades.get(id) - 1);
        if (s.grenades.get(id) <= 0) {
            s.grenades.remove(id);
            if (!s.hasSlot(CsState.SLOT_GRENADE)) s.slot = s.hasSlot(CsState.SLOT_PRIMARY) ? CsState.SLOT_PRIMARY : CsState.SLOT_PISTOL;
        }
        save(p, s);
        Vec3d dir = a.nums().size() >= 3 ? new Vec3d(a.nums().get(0), a.nums().get(1), a.nums().get(2)).normalize() : p.getRotationVec(1f);
        GrenadeEntity e = new GrenadeEntity(GRENADE, p.getEntityWorld());
        e.setOwner(p);
        e.setKind(id);
        Vec3d eye = p.getEyePos().add(dir.multiply(0.4));
        e.setPosition(eye.x, eye.y - 0.1, eye.z);
        e.setVelocity(dir.multiply(1.15).add(0, 0.12, 0).add(p.getVelocity().multiply(1, 0, 1)));
        p.getEntityWorld().spawnEntity(e);
    }

    static void addSmoke(GrenadeEntity.Smoke s) { SMOKES.add(s); }

    static void recordGrenadeHit(PlayerEntity owner, LivingEntity t, String kind) {
        if (owner != null) LAST_HIT.put(t.getUuid(), new LastHit(owner.getUuid(), kind, false, System.currentTimeMillis()));
    }

    // ---------------------------------------------------------------- buying

    private static void buy(ServerPlayerEntity p, CsState s, String id) {
        if (!s.csMode) { deny(p, "mode"); return; }
        Sheets.Weapon w = Sheets.weapon(id);
        if (w != null) {
            if (w.isKnife()) { deny(p, "have"); return; }
            if (s.level < w.unlockLevel()) { deny(p, "level"); return; }
            if ((w.slot() == CsState.SLOT_PRIMARY ? s.primary : s.pistol).equals(id)) { deny(p, "have"); return; }
            if (!pay(p, s, w.price())) return;
            if (w.slot() == CsState.SLOT_PRIMARY) s.primary = id; else s.pistol = id;
            s.clips.put(id, w.clip());
            s.slot = w.slot();
            save(p, s);
            send(p, "sound", "items/gunpickup2.wav");
            return;
        }
        if (id.startsWith("ammo")) {
            String ammoId = switch (id) {
                case "ammo_primary" -> s.primary.isEmpty() ? "" : Sheets.weapon(s.primary).ammo();
                case "ammo_secondary" -> s.pistol.isEmpty() ? "" : Sheets.weapon(s.pistol).ammo();
                default -> id.startsWith("ammo:") ? id.substring(5) : "";
            };
            Sheets.Ammo am = Sheets.AMMO.get(ammoId);
            if (am == null || !usesAmmo(s, ammoId)) { deny(p, "noweapon"); return; }
            int have = s.reserveOf(ammoId);
            if (have >= am.maxReserve()) { deny(p, "full"); return; }
            if (!pay(p, s, am.packPrice())) return;
            s.reserve.put(ammoId, Math.min(am.maxReserve(), have + am.packSize()));
            save(p, s);
            send(p, "sound", "items/9mmclip1.wav");
            return;
        }
        Sheets.Equipment e = Sheets.EQUIPMENT.get(id);
        if (e == null) return;
        if (s.level < e.unlockLevel()) { deny(p, "level"); return; }
        switch (id) {
            case "vest" -> {
                if (s.armor >= 100) { deny(p, "have"); return; }
                if (!pay(p, s, e.price())) return;
                s.armor = 100;
            }
            case "vesthelm" -> {
                if (s.armor >= 100 && s.helmet) { deny(p, "have"); return; }
                int price = s.armor >= 100 ? e.price() - Sheets.EQUIPMENT.get("vest").price() : e.price();
                if (!pay(p, s, price)) return;
                s.armor = 100;
                s.helmet = true;
            }
            case "c4" -> {
                if (s.hasBomb) { deny(p, "have"); return; }
                if (!pay(p, s, e.price())) return;
                s.hasBomb = true;
            }
            default -> {
                if (!e.isGrenade()) return;
                if (s.grenades.getOrDefault(id, 0) >= e.max()) { deny(p, "have"); return; }
                if (!pay(p, s, e.price())) return;
                s.grenades.merge(id, 1, Integer::sum);
            }
        }
        save(p, s);
        send(p, "sound", e.isGrenade() ? "items/gunpickup2.wav" : "items/kevlar.wav");
    }

    static boolean usesAmmo(CsState s, String ammoId) {
        return (!s.primary.isEmpty() && Sheets.weapon(s.primary).ammo().equals(ammoId))
                || (!s.pistol.isEmpty() && Sheets.weapon(s.pistol).ammo().equals(ammoId));
    }

    private static boolean pay(ServerPlayerEntity p, CsState s, int price) {
        if (s.money < price) { deny(p, "money"); return false; }
        s.money -= price;
        return true;
    }

    private static void deny(ServerPlayerEntity p, String why) { send(p, "denied", why); }

    // ---------------------------------------------------------------- money and XP

    /** economy.json awards; money -1 means "the weapon's killReward". */
    static void award(ServerPlayerEntity p, String awardId, int weaponReward) {
        Sheets.Award aw = Sheets.AWARDS.get(awardId);
        if (aw == null) return;
        int money = aw.money() < 0 ? weaponReward : aw.money();
        CsState s = state(p);
        s.money = (int) Math.min(Sheets.eco("maxMoney"), s.money + money);
        s.xp += aw.xp();
        int gained = 0;
        while (s.level < Sheets.eco("maxLevel") && s.xp >= Sheets.xpForNextLevel(s.level)) {
            s.xp -= Sheets.xpForNextLevel(s.level);
            s.level++;
            gained++;
            s.money = (int) Math.min(Sheets.eco("maxMoney"), s.money + Sheets.eco("levelUpBonus"));
        }
        save(p, s);
        send(p, "award", awardId, money, aw.xp(), gained, s.level);
    }

    public static void onAdvancement(ServerPlayerEntity p) { award(p, "advancement", 0); }

    private static void onDeath(LivingEntity victim, DamageSource source) {
        LastHit lh = LAST_HIT.remove(victim.getUuid());
        if (victim instanceof ServerPlayerEntity vp) live(vp).aliveSinceDusk = false;
        if (lh == null || System.currentTimeMillis() - lh.at() > 5000) {
            KillFeed.onDeath(victim, source);
            return;
        }
        MinecraftServer server = victim.getEntityWorld().getServer();
        if (server == null) return;
        ServerPlayerEntity killer = server.getPlayerManager().getPlayer(lh.attacker());
        if (killer == null || killer == victim) return;
        Sheets.Weapon w = Sheets.weapon(lh.weapon());
        int reward = w != null ? w.killReward() : 300;
        if (victim instanceof PlayerEntity) award(killer, "kill_player", reward);
        else if (victim instanceof Monster) award(killer, "kill_hostile", reward);
        else award(killer, "kill_passive", 0);
        if (lh.headshot()) award(killer, "headshot", 0);
        KillFeed.onCsKill(killer, victim, lh.weapon(), lh.headshot());
    }

    static boolean isPrecious(BlockState st) {
        return st.isOf(Blocks.DIAMOND_ORE) || st.isOf(Blocks.DEEPSLATE_DIAMOND_ORE) || st.isOf(Blocks.EMERALD_ORE)
                || st.isOf(Blocks.DEEPSLATE_EMERALD_ORE) || st.isOf(Blocks.ANCIENT_DEBRIS);
    }

    // ---------------------------------------------------------------- tick

    private static void tick(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        long tod = Math.floorMod(overworld.getTimeOfDay(), 24000L);
        Set<UUID> online = new HashSet<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            online.add(p.getUuid());
            CsState s = state(p);
            applySpeed(p, s);
            Live live = live(p);
            if (lastTimeOfDay >= 0) {
                if (lastTimeOfDay < 13000 && tod >= 13000 && p.isAlive()) live.aliveSinceDusk = true;
                if (lastTimeOfDay < 23500 && tod >= 23500 && live.aliveSinceDusk && p.isAlive()) {
                    live.aliveSinceDusk = false;
                    award(p, "round_survived", 0);
                }
            }
        }
        lastTimeOfDay = tod;
        LIVE.keySet().retainAll(online);
        long now = System.currentTimeMillis();
        LAST_HIT.values().removeIf(h -> now - h.at() > 10000);
        SMOKES.removeIf(GrenadeEntity.Smoke::tick);
        BlockDamage.tick();
    }

    /** CS move speed of the weapon in hand (250 units/s = full speed), only in CS mode. */
    private static void applySpeed(ServerPlayerEntity p, CsState s) {
        EntityAttributeInstance inst = p.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        if (inst == null) return;
        Sheets.Weapon w = s.current();
        double mul = (s.csMode && w != null) ? w.speed() / 250.0 - 1.0 : 0.0;
        EntityAttributeModifier cur = inst.getModifier(SPEED_ID);
        if (Math.abs(mul) < 1e-4) {
            if (cur != null) inst.removeModifier(SPEED_ID);
        } else if (cur == null || Math.abs(cur.value() - mul) > 1e-4) {
            inst.overwritePersistentModifier(new EntityAttributeModifier(SPEED_ID, mul, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** Armor (CS kevlar): called from the damage mixin with Minecraft damage; returns the damage left. */
    public static float absorb(ServerPlayerEntity p, DamageSource src, float amount) {
        if (amount <= 0) return amount;
        boolean protects = src.getAttacker() != null || src.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_EXPLOSION) || src.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_PROJECTILE);
        if (!protects) return amount;
        CsState s = state(p);
        if (s.armor <= 0 || (HEAD_HIT.get() && !s.helmet)) return amount;
        float ratio = (float) Sheets.eco("armorRatio");
        float left = amount * (1 - ratio);
        float absorbedCs = (amount - left) / (float) Sheets.eco("damageScale");
        int cost = Math.max(1, Math.round(absorbedCs * 0.5f));
        if (cost > s.armor) {
            left = amount - s.armor * 2 * (float) Sheets.eco("damageScale");
            s.armor = 0;
            s.helmet = false;
        } else {
            s.armor -= cost;
        }
        save(p, s);
        return Math.max(0, left);
    }

    static Entity entity(ServerWorld w, UUID id) { return w.getEntity(id); }

    static float clamp(float v, float lo, float hi) { return MathHelper.clamp(v, lo, hi); }
}
