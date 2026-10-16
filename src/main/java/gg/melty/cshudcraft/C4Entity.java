package gg.melty.cshudcraft;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * A planted CS C4 (equipment.json "c4"): sits where it was planted, beeps faster and faster with the
 * player's own CS beep sounds, and after 45 s blows up — players, mobs and blocks around it. Players
 * defuse it by holding use on it for 10 s (CsServer). Not saved with the world.
 */
public class C4Entity extends Entity {
    public static final int FUSE_TICKS = 45 * 20;
    static final double RADIUS = 12, BLOCK_RADIUS = 5;
    private static final TrackedData<Integer> TICKS_LEFT = DataTracker.registerData(C4Entity.class, TrackedDataHandlerRegistry.INTEGER);

    UUID planter;
    private int nextBeep;

    public C4Entity(EntityType<? extends C4Entity> type, World world) {
        super(type, world);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(TICKS_LEFT, FUSE_TICKS);
    }

    public int ticksLeft() { return dataTracker.get(TICKS_LEFT); }

    @Override
    public void tick() {
        super.tick();
        if (!getEntityWorld().isClient()) {
            if (!isOnGround()) {
                setVelocity(getVelocity().add(0, -0.04, 0).multiply(0.9));
                move(net.minecraft.entity.MovementType.SELF, getVelocity());
            }
            int left = ticksLeft() - 1;
            dataTracker.set(TICKS_LEFT, left);
            ServerWorld w = (ServerWorld) getEntityWorld();
            if (left <= 0) {
                explode(w);
                discard();
                return;
            }
            // CS: slow beeps at first, then faster; the pitch file changes as time runs out
            if (--nextBeep <= 0) {
                float t = 1f - left / (float) FUSE_TICKS;
                nextBeep = Math.max(2, Math.round(30 * (1 - t) * (1 - t) + 2));
                int file = Math.min(5, 1 + (int) (t * 5));
                CsServer.soundAround(w, getEntityPos(), "weapons/c4_beep" + file + ".wav", 0.8f, null, 40);
            }
        }
    }

    private void explode(ServerWorld w) {
        Vec3d c = getEntityPos().add(0, 0.3, 0);
        for (int i = 0; i < 6; i++) {
            w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x + random.nextGaussian() * 2, c.y + random.nextDouble() * 2, c.z + random.nextGaussian() * 2, 1, 0, 0, 0, 0);
        }
        w.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 1, c.z, 80, 3, 2, 3, 0.05);
        CsServer.soundAround(w, c, "weapons/c4_explode1.wav", 1f, null, 200);
        ServerPlayerEntity owner = planter == null ? null : w.getServer().getPlayerManager().getPlayer(planter);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(RADIUS), LivingEntity::isAlive)) {
            double d = e.getBoundingBox().getCenter().distanceTo(c);
            if (d > RADIUS) continue;
            float cs = (float) (500 * (1 - d / RADIUS));
            CsServer.recordGrenadeHit(owner, e, "c4");
            e.timeUntilRegen = 0;
            Entity src = owner != null ? owner : this;
            e.damage(w, w.getDamageSources().explosion(this, src), cs * (float) Sheets.eco("damageScale"));
        }
        BlockPos center = BlockPos.ofFloored(c);
        int r = (int) Math.ceil(BLOCK_RADIUS);
        for (BlockPos bp : BlockPos.iterate(center.add(-r, -r, -r), center.add(r, r, r))) {
            double d = Vec3d.ofCenter(bp).distanceTo(c);
            if (d <= BLOCK_RADIUS) BlockDamage.damage(owner, w, bp.toImmutable(), (float) (400 * (1 - d / (BLOCK_RADIUS + 1))));
        }
        if (owner != null) CsServer.award(owner, "bomb_exploded", 0);
        for (ServerPlayerEntity p : w.getPlayers()) CsServer.send(p, "sound", "radio/terwin.wav");
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) { return false; }

    @Override
    public boolean canHit() { return true; }

    @Override
    public boolean shouldSave() { return false; }

    @Override
    protected void readCustomData(ReadView view) { }

    @Override
    protected void writeCustomData(WriteView view) { }

    static boolean isPlayer(Entity e) { return e instanceof PlayerEntity; }
}
