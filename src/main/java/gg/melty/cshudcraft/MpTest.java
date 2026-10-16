package gg.melty.cshudcraft;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dev check for two copies of the game on one PC (CSHUDCRAFT_MPTEST=host / join), never active for
 * players. The host puts both players on a platform and attacks the other with a Deagle, the knife, an
 * HE grenade, a bullet kill and a C4; the joiner reports what it sees and hears of the host. Every
 * result is a "[CSHudCraft] mp-test: pass|fail <check>" line in each copy's log.
 */
final class MpTest {
    static final String ROLE = System.getenv("CSHUDCRAFT_MPTEST");

    private static final Set<String> reported = new HashSet<>();
    private static int stage, stageTicks, otherTicks;
    private static float healthMark;
    private static int moneyMark;
    private static int c4Id = -1;
    private static int killWait;
    private static BlockPos base;
    private static int framesMark = -1;
    private static boolean shotAk;
    private static volatile boolean killSeen, c4KillSeen;
    private static volatile int shotSounds, heSounds, c4Sounds;

    private MpTest() {}

    static boolean active() { return ROLE != null; }

    static void onSound(String path) {
        if (ROLE == null) return;
        if (path.startsWith("weapons/deagle") || path.startsWith("weapons/ak47")) shotSounds++;
        else if (path.startsWith("weapons/hegrenade")) heSounds++;
        else if (path.startsWith("weapons/c4_")) c4Sounds++;
    }

    static void onKill(String killer, String icon, String victim) {
        if (ROLE == null) return;
        CsHudCraft.LOG.info("[CSHudCraft] mp-test: kill feed: {} [{}] {}", killer, icon, victim);
        if (!killer.isEmpty() && !victim.isEmpty() && !killer.equals(victim)) {
            if (icon.equals("d_grenade")) c4KillSeen = true; else killSeen = true;
        }
    }

    private static void report(String check, String failure) {
        if (!reported.add(check)) return;
        CsHudCraft.LOG.info("[CSHudCraft] mp-test: {}", failure == null ? "pass " + check : "fail " + check + ": " + failure);
    }

    private static void shot(MinecraftClient c, String name) {
        net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(c.runDirectory, "mp_" + name + ".png", c.getFramebuffer(), 1, t -> { });
    }

    /** What the host's own CS state looks like, for reading a failed check. */
    private static void diag(MinecraftClient c, String what) {
        CsState st = ClientCs.state();
        CsHudCraft.LOG.info("[CSHudCraft] mp-test: {}: shots {}, slot {}, held {}, clip {}, cs {}, pos {}, target {}", what, CsHudCraft.shots(),
                st == null ? -1 : st.slot, ThirdPersonWeapon.heldOf(c.player), st == null || st.current() == null ? -1 : st.clip(st.current().id()),
                ClientCs.takesInput() + "/" + CsHudCraft.enabled() + "/" + CsHudCraft.ASSETS.loaded() + "/" + c.player.isSpectator() + "/" + (st == null ? "?" : st.csMode) + "/" + c.currentScreen, c.player.getEntityPos(), other(c) == null ? null : other(c).getEntityPos());
    }

    private static void next(int s) {
        stage = s;
        stageTicks = 0;
    }

    private static void aim(ClientPlayerEntity p, Vec3d at) {
        Vec3d d = at.subtract(p.getEyePos());
        p.setYaw((float) (MathHelper.atan2(d.z, d.x) * 57.2958) - 90f);
        p.setPitch((float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 57.2958));
    }

    private static PlayerEntity other(MinecraftClient c) {
        for (PlayerEntity o : c.world.getPlayers()) if (o != c.player) return o;
        return null;
    }

    static void tick(MinecraftClient c) {
        if (ROLE == null || c.player == null || c.world == null) return;
        if (!CsHudCraft.enabled()) CsHudCraft.setEnabled(true, false); // the check plays in CS mode whatever F6 left saved
        if (ROLE.equals("join")) joinTick(c);
        else if (c.getServer() != null) hostTick(c, c.getServer());
    }

    // ---------------------------------------------------------------- joiner: what the other player sees

    private static void joinTick(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        if (c.currentScreen instanceof DeathScreen || p.isDead()) {
            if (!reported.contains("dies")) report("dies", null);
            p.requestRespawn();
            c.setScreen(null);
            return;
        }
        if (healthMark == 0) healthMark = p.getMaxHealth();
        if (p.getHealth() < healthMark - 0.5f) report("takes-damage", null);
        PlayerEntity host = other(c);
        if (host == null) return;
        aim(p, host.getEyePos().add(0, -0.4, 0));
        String held = ThirdPersonWeapon.heldOf(host);
        if (!held.isEmpty()) {
            if (framesMark < 0) framesMark = ThirdPersonWeapon.framesDrawn();
            else if (ThirdPersonWeapon.framesDrawn() - framesMark > 40) report("sees-weapon", null);
            if (held.equals("ak47") && !shotAk && ThirdPersonWeapon.framesDrawn() - framesMark > 20) {
                shotAk = true;
                shot(c, "join_sees_host_ak47");
            }
        }
        for (Entity e : c.world.getEntities()) {
            if (e instanceof GrenadeEntity) report("sees-grenade", null);
            if (e instanceof C4Entity && !reported.contains("sees-c4")) { report("sees-c4", null); shot(c, "join_sees_c4"); }
        }
        if (shotSounds > 0) report("hears-shots", null);
        if (heSounds > 0) report("hears-he", null);
        if (c4Sounds > 0) report("hears-c4", null);
        if (killSeen) report("kill-feed", null);
        if (c4KillSeen) report("kill-feed-c4", null);
    }

    // ---------------------------------------------------------------- host: attacks the other player

    private static ServerPlayerEntity serverSelf(IntegratedServer s, MinecraftClient c) {
        return s.getPlayerManager().getPlayer(c.player.getUuid());
    }

    private static ServerPlayerEntity serverOther(IntegratedServer s, MinecraftClient c) {
        for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) if (!o.getUuid().equals(c.player.getUuid())) return o;
        return null;
    }

    /** Both players back on the platform, host facing the joiner 6 blocks away, the joiner healed. */
    private static void place(IntegratedServer s, MinecraftClient c, double hostZ) {
        s.execute(() -> {
            ServerPlayerEntity me = serverSelf(s, c), them = serverOther(s, c);
            if (me == null || them == null) return;
            ServerWorld w = me.getEntityWorld();
            if (base == null) {
                base = me.getBlockPos().up(30);
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 10; z++) w.setBlockState(base.add(x, 0, z), Blocks.BEDROCK.getDefaultState());
            }
            me.teleport(w, base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5 + hostZ, Set.of(), 0f, 0f, false);
            if (them.isAlive()) {
                if (them.getEntityWorld() != w || them.getBlockPos().getManhattanDistance(base) > 12) {
                    them.teleport(w, base.getX() + 0.5, base.getY() + 1, base.getZ() + 6.5, Set.of(), 180f, 0f, false);
                }
                them.setHealth(them.getMaxHealth());
                them.changeGameMode(GameMode.SURVIVAL);
                CsState ts = CsServer.state(them);
                ts.armor = 0;
                ts.helmet = false;
                CsServer.save(them, ts);
            }
        });
    }

    private static float otherHealth(IntegratedServer s, MinecraftClient c) {
        ServerPlayerEntity o = serverOther(s, c);
        return o == null ? -1 : o.getHealth();
    }

    private static void hostTick(MinecraftClient c, IntegratedServer s) {
        ClientPlayerEntity p = c.player;
        stageTicks++;
        ServerPlayerEntity them = serverOther(s, c);
        PlayerEntity target = other(c);
        Vec3d chest = target == null ? null : target.getEyePos().add(0, -0.5, 0);
        switch (stage) {
            case 0 -> { // wait for the second copy
                if (them == null || target == null) { otherTicks = 0; return; }
                if (++otherTicks < 100) return;
                report("other-joined", null);
                s.execute(() -> {
                    ServerPlayerEntity me = serverSelf(s, c);
                    if (me == null) return;
                    me.changeGameMode(GameMode.SURVIVAL);
                    CsState st = CsServer.state(me);
                    st.money = 10000; // under the 16000 cap, so the kill reward shows
                    st.level = (int) Sheets.eco("maxLevel");
                    st.primary = "ak47";
                    st.clips.put("ak47", Sheets.weapon("ak47").clip());
                    st.pistol = "deagle";
                    st.clips.put("deagle", Sheets.weapon("deagle").clip());
                    st.reserve.put("50ae", 35);
                    st.grenades.put("hegrenade", 1);
                    st.hasBomb = true;
                    st.slot = CsState.SLOT_PRIMARY;
                    CsServer.save(me, st);
                });
                place(s, c, 0);
                next(1);
            }
            case 1 -> { // AK out for the joiner to see
                if (chest != null) aim(p, chest);
                if (stageTicks == 5) ClientCs.select(CsState.SLOT_PRIMARY);
                if (stageTicks > 80) { ClientCs.select(CsState.SLOT_PISTOL); healthMark = otherHealth(s, c); next(2); }
            }
            case 2 -> { // a Deagle bullet
                if (chest != null) aim(p, chest);
                if (stageTicks >= 30 && stageTicks % 6 == 0) TestMode.trigger = true;
                if (stageTicks == 30 || stageTicks == 90) diag(c, "bullet");
                if (stageTicks > 30 && otherHealth(s, c) < healthMark - 0.5f) { report("pvp-bullet", null); place(s, c, 4.5); next(3); }
                else if (stageTicks > 120) { report("pvp-bullet", "health " + otherHealth(s, c) + " of " + healthMark); place(s, c, 4.5); next(3); }
            }
            case 3 -> { // the knife from 1.5 blocks
                if (stageTicks == 10) { ClientCs.select(CsState.SLOT_KNIFE); healthMark = -1; }
                if (chest != null) aim(p, chest);
                if (stageTicks == 40) { healthMark = otherHealth(s, c); diag(c, "knife"); }
                if (stageTicks >= 40 && stageTicks % 8 == 0) TestMode.trigger = true;
                if (stageTicks > 40 && otherHealth(s, c) < healthMark - 0.5f) { report("pvp-knife", null); place(s, c, 0); next(4); }
                else if (stageTicks > 140) { report("pvp-knife", "health " + otherHealth(s, c) + " of " + healthMark); place(s, c, 0); next(4); }
            }
            case 4 -> { // an HE grenade at the joiner's feet
                if (stageTicks == 10) ClientCs.select(CsState.SLOT_GRENADE);
                if (target != null) aim(p, target.getEntityPos().add(0, 0.6, -1.0));
                if (stageTicks == 30) healthMark = otherHealth(s, c);
                if (stageTicks >= 30 && stageTicks < 60) TestMode.trigger = true;
                if (stageTicks == 30 || stageTicks == 70) diag(c, "he");
                if (stageTicks > 60 && otherHealth(s, c) < healthMark - 0.5f) { report("pvp-he", null); place(s, c, 0); next(5); }
                else if (stageTicks > 200) { report("pvp-he", "health " + otherHealth(s, c) + " of " + healthMark); place(s, c, 0); next(5); }
            }
            case 5 -> { // a bullet kill: kill reward and kill feed
                if (stageTicks == 10) ClientCs.select(CsState.SLOT_PISTOL);
                if (chest != null) aim(p, chest);
                if (stageTicks == 30) s.execute(() -> {
                    ServerPlayerEntity o = serverOther(s, c), me = serverSelf(s, c);
                    if (o != null) o.setHealth(3f);
                    if (me != null) moneyMark = CsServer.state(me).money;
                });
                if (stageTicks >= 40 && stageTicks % 6 == 0) TestMode.trigger = true;
                if (stageTicks > 40 && (them == null || !them.isAlive() || killSeen)) {
                    if (!killSeen && killWait++ < 40) return;
                    int reward = CsServer.state(serverSelf(s, c)).money - moneyMark;
                    report("kill-reward", reward > 0 ? null : "money changed by " + reward);
                    report("kill-feed", killSeen ? null : "no kill notice reached the host");
                    next(6);
                } else if (stageTicks > 240) { report("kill-reward", "the other player did not die"); report("kill-feed", "no kill"); next(6); }
            }
            case 6 -> { // wait for the respawn, then plant a C4 at the joiner's feet with a short fuse
                if (them == null || !them.isAlive()) return;
                if (stageTicks == 60) place(s, c, 4.5);
                if (stageTicks < 100) return;
                if (stageTicks == 100) { ClientCs.select(CsState.SLOT_BOMB); healthMark = otherHealth(s, c); }
                p.setPitch(80f);
                TestMode.trigger = true;
                C4Entity bomb = c4Id >= 0 ? null : s.getOverworld().getEntitiesByClass(C4Entity.class, p.getBoundingBox().expand(4), e -> true).stream().findFirst().orElse(null);
                if (bomb != null) {
                    c4Id = bomb.getId();
                    report("c4-planted", null);
                    s.execute(() -> {
                        bomb.shortenFuse(5 * 20);
                        ServerPlayerEntity me = serverSelf(s, c);
                        if (me != null) me.changeGameMode(GameMode.CREATIVE); // the host stays out of its own blast
                    });
                    next(7);
                } else if (stageTicks > 300) { report("c4-planted", "no C4 after holding fire"); next(8); }
            }
            case 7 -> { // the blast reaches the joiner 1.5 blocks away
                if (them == null || !them.isAlive() || c4KillSeen) {
                    report("pvp-c4", null);
                    next(8);
                } else if (stageTicks > 400) { report("pvp-c4", "the other player survived the C4 (health " + otherHealth(s, c) + ")"); next(8); }
            }
            case 8 -> {
                if (stageTicks == 40) {
                    report("kill-feed-c4", c4KillSeen ? null : "no C4 kill notice");
                    CsHudCraft.LOG.info("[CSHudCraft] mp-test: host finished, {} checks", reported.size());
                }
            }
            default -> { }
        }
    }

    static List<String> reported() { return List.copyOf(reported); }
}
