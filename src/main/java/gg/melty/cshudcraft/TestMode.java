package gg.melty.cshudcraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Melty's test mode (sheets/systems.json "test_checks"). Only active while
 * %LOCALAPPDATA%/Melty/test/cs-1-6-craft.json exists: makes a world and plays the main scenarios
 * by itself, appending "pass <check>" / "fail <check>: why" lines. It never quits the game.
 */
final class TestMode {
    static final String SLUG = "cs-1-6-craft";

    private static boolean active;
    private static long seed;
    private static Path checksFile;
    private static final Set<String> reported = new HashSet<>();
    private static boolean worldRequested;
    private static int stage, wait, inWorld, stageTicks, mark;
    private static int moneyMark;
    private static UUID zombie;
    private static net.minecraft.util.math.BlockPos obsidian;
    static boolean trigger;
    /** Dev check only: CSHUDCRAFT_SHOTS=1 saves screenshots of each step and plays a longer showcase. */
    private static final boolean SHOTS = "1".equals(System.getenv("CSHUDCRAFT_SHOTS"));

    static boolean shotsMode() { return SHOTS; }

    private static void shot(MinecraftClient c, String name) {
        if (!SHOTS) return;
        net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(c.runDirectory, "cs_" + name + ".png", c.getFramebuffer(), 1, t -> { });
    }

    private TestMode() {}

    static void init() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null) return;
        Path f = Paths.get(local, "Melty", "test", SLUG + ".json");
        if (!Files.isRegularFile(f)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            seed = o.has("seed") ? o.get("seed").getAsLong() : 1L;
            checksFile = Paths.get(o.get("checks").getAsString());
            active = true;
            CsHudCraft.LOG.info("[CSHudCraft] Melty test mode, run {} of {}", o.has("run") ? o.get("run") : "?", o.has("runs") ? o.get("runs") : "?");
        } catch (Exception e) {
            CsHudCraft.LOG.warn("[CSHudCraft] Could not read Melty test file: {}", e.toString());
        }
    }

    static void onAssets() {
        if (!active) return;
        CsHudCraft.setEnabled(true, false);
        if (CsHudCraft.ASSETS.loaded() && ViewModelRenderer.model("v_knife.mdl") != null) report("cs-files-found", null);
        else report("cs-files-found", CsHudCraft.ASSETS.loaded() ? "v_knife.mdl could not be read" : CsHudCraft.ASSETS.error());
    }

    static void onScreen(MinecraftClient client, Screen screen) {
        if (!active || worldRequested || !(screen instanceof TitleScreen)) return;
        worldRequested = true;
        client.execute(() -> {
            String name = "CSHudCraft Test " + System.currentTimeMillis();
            LevelInfo info = new LevelInfo(name, GameMode.SURVIVAL, false, Difficulty.EASY, false,
                    new GameRules(DataConfiguration.SAFE_MODE.enabledFeatures()), DataConfiguration.SAFE_MODE);
            client.createIntegratedServerLoader().createAndStart(name, info,
                    new GeneratorOptions(seed, true, false), WorldPresets::createDemoOptions, screen);
        });
    }

    private static ServerPlayerEntity serverPlayer(MinecraftClient c) {
        IntegratedServer s = c.getServer();
        return s == null || s.getPlayerManager().getPlayerList().isEmpty() ? null : s.getPlayerManager().getPlayerList().get(0);
    }

    private static void next(int st, int w) {
        stage = st;
        wait = w;
        stageTicks = 0;
    }

    static void tick(MinecraftClient c) {
        trigger = false;
        if (!active || c.player == null || c.world == null || (c.currentScreen != null && !(c.currentScreen instanceof BuyScreen))) return;
        inWorld++;
        if (wait > 0) { wait--; return; }
        stageTicks++;
        ClientPlayerEntity p = c.player;
        CsState st = ClientCs.state();
        IntegratedServer server = c.getServer();
        if (st == null || server == null) return;
        switch (stage) {
            case 0 -> {
                if (inWorld < 100) return;
                if (!CsHudCraft.enabled()) CsHudCraft.setEnabled(true, false);
                ClientCs.select(CsState.SLOT_KNIFE);
                mark = ViewModelRenderer.framesDrawn();
                next(1, 0);
            }
            case 1 -> {
                if (ViewModelRenderer.framesDrawn() - mark >= 40) { report("viewmodel-drawn", null); shot(c, "1_knife"); next(2, 20); }
                else if (stageTicks > 400) { report("viewmodel-drawn", "the CS knife viewmodel was not drawn"); next(2, 0); }
            }
            case 2 -> { // buy at spawn
                moneyMark = st.money;
                ClientCs.send("buy", "deagle");
                next(3, 10);
            }
            case 3 -> {
                if (st.pistol.equals("deagle") && st.money == moneyMark - Sheets.weapon("deagle").price()) {
                    server.execute(() -> { // somewhere else entirely: 300 blocks away
                        ServerPlayerEntity sp = serverPlayer(c);
                        if (sp == null) return;
                        ServerWorld w = sp.getEntityWorld();
                        int x = (int) sp.getX() + 300, z = (int) sp.getZ() + 120;
                        w.getChunk(x >> 4, z >> 4);
                        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                        sp.requestTeleport(x + 0.5, y + 0.1, z + 0.5);
                    });
                    next(4, 80);
                } else if (stageTicks > 200) { report("buy-anywhere", "buying the Desert Eagle at spawn did not go through (money " + st.money + ")"); next(6, 0); }
            }
            case 4 -> {
                moneyMark = st.money;
                ClientCs.send("buy", "ammo:50ae");
                next(5, 10);
            }
            case 5 -> {
                Sheets.Ammo am = Sheets.AMMO.get("50ae");
                if (st.reserveOf("50ae") == am.packSize() && st.money == moneyMark - am.packPrice()) { report("buy-anywhere", null); shot(c, "2_bought_far"); next(6, 0); }
                else if (stageTicks > 200) { report("buy-anywhere", "ammo bought 300 blocks away: reserve " + st.reserveOf("50ae") + ", money " + st.money); next(6, 0); }
            }
            case 6 -> { // a zombie in front, Deagle out
                ClientCs.select(CsState.SLOT_PISTOL);
                Vec3d eye = p.getEyePos(), look = Vec3d.fromPolar(0, p.getYaw()).multiply(5);
                Vec3d at = eye.add(look.x, 0, look.z);
                server.execute(() -> {
                    ServerPlayerEntity sp = serverPlayer(c);
                    if (sp == null) return;
                    ServerWorld w = sp.getEntityWorld();
                    ZombieEntity zb = EntityType.ZOMBIE.create(w, SpawnReason.TRIGGERED);
                    if (zb == null) return;
                    zb.refreshPositionAndAngles(at.x, w.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(at.x), (int) Math.floor(at.z)), at.z, 0, 0);
                    zb.setAiDisabled(true);
                    zb.setPersistent();
                    w.spawnEntity(zb);
                    zombie = zb.getUuid();
                });
                next(7, 40);
            }
            case 7 -> {
                ServerPlayerEntity sp = serverPlayer(c);
                var target = sp == null || zombie == null ? null : sp.getEntityWorld().getEntity(zombie);
                if (target == null) { if (stageTicks > 100) { report("shoot-hit", "the test zombie did not spawn"); next(9, 0); } return; }
                Vec3d aim = target.getBoundingBox().getCenter().add(0, 0.2, 0).subtract(p.getEyePos());
                p.setYaw((float) (MathHelper.atan2(aim.z, aim.x) * 57.2958) - 90f);
                p.setPitch((float) -(MathHelper.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z)) * 57.2958));
                if (stageTicks > 25) {
                    mark = CsHudCraft.shots();
                    trigger = true;
                    next(8, 0);
                }
            }
            case 8 -> {
                ServerPlayerEntity sp = serverPlayer(c);
                var target = sp == null ? null : sp.getEntityWorld().getEntity(zombie);
                boolean hurt = target == null || !target.isAlive() || ((ZombieEntity) target).getHealth() < ((ZombieEntity) target).getMaxHealth();
                if (CsHudCraft.shots() > mark && hurt) { report("shoot-hit", null); shot(c, "3_deagle_hit"); next(9, 20); }
                else if (stageTicks > 120) { report("shoot-hit", "shots fired: " + (CsHudCraft.shots() - mark) + ", zombie hurt: " + hurt); next(9, 0); }
                else if (CsHudCraft.shots() == mark) trigger = true;
            }
            case 9 -> {
                mark = st.reserveOf("50ae");
                ClientCs.startReload();
                next(10, 0);
            }
            case 10 -> {
                CsState synced = p.getAttached(CsNet.STATE);
                int clipMax = Sheets.weapon("deagle").clip();
                if (synced != null && synced.clip("deagle") == clipMax && synced.reserveOf("50ae") < mark) { report("reload", null); next(15, 20); }
                else if (stageTicks > 200) { report("reload", "clip " + (synced == null ? -1 : synced.clip("deagle")) + "/" + clipMax); next(15, 0); }
            }
            case 11 -> { // F6 to Minecraft's interface and back; nothing may change
                moneyMark = st.money;
                CsHudCraft.setEnabled(false, false);
                next(12, 40);
            }
            case 12 -> {
                boolean vanilla = CsHudCraft.vanillaDrawn();
                shot(c, "5_minecraft_mode");
                mark = CsHudCraft.csFrames();
                CsHudCraft.setEnabled(true, false);
                if (!vanilla) { report("mode-toggle", "Minecraft's HUD did not show in Minecraft mode"); next(14, 0); }
                else next(13, 30);
            }
            case 13 -> {
                CsState now = ClientCs.state();
                if (CsHudCraft.csFrames() > mark && now.money == moneyMark && now.pistol.equals("deagle")) report("mode-toggle", null);
                else report("mode-toggle", "after switching back: frames " + (CsHudCraft.csFrames() - mark) + ", money " + now.money + " (was " + moneyMark + ")");
                shot(c, "6_cs_back");
                next(SHOTS ? 20 : 99, 0);
            }
            case 15 -> { // obsidian 3 blocks ahead at eye height
                Vec3d look = Vec3d.fromPolar(0, p.getYaw());
                net.minecraft.util.math.BlockPos bp = net.minecraft.util.math.BlockPos.ofFloored(p.getEyePos().add(look.multiply(3)));
                obsidian = bp;
                server.execute(() -> {
                    ServerPlayerEntity sp = serverPlayer(c);
                    if (sp != null) sp.getEntityWorld().setBlockState(bp, net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
                });
                mark = CsHudCraft.shots();
                next(16, 10);
            }
            case 16 -> {
                ServerPlayerEntity sp = serverPlayer(c);
                if (sp == null) return;
                ServerWorld w = sp.getEntityWorld();
                boolean gone = !w.getBlockState(obsidian).isOf(net.minecraft.block.Blocks.OBSIDIAN);
                int fired = CsHudCraft.shots() - mark;
                if (gone) {
                    if (fired >= 2) report("block-break", null);
                    else report("block-break", "obsidian broke after " + fired + " shot(s), expected several");
                    shot(c, "4_obsidian_broken");
                    next(11, 20);
                    return;
                }
                if (fired == 2) shot(c, "4_obsidian_cracked");
                if (fired >= 7 || stageTicks > 300) {
                    report("block-break", "obsidian still standing after " + fired + " Deagle shots (" + Math.round(BlockDamage.progress(w, obsidian) * 100) + "% damaged)");
                    next(11, 20);
                    return;
                }
                Vec3d aim = Vec3d.ofCenter(obsidian).subtract(p.getEyePos());
                p.setYaw((float) (MathHelper.atan2(aim.z, aim.x) * 57.2958) - 90f);
                p.setPitch((float) -(MathHelper.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z)) * 57.2958));
                trigger = (stageTicks % 8) == 0; // Deagle is semi-automatic: press, release
            }
            case 20 -> { // showcase: rich, max level
                server.execute(() -> {
                    ServerPlayerEntity sp = serverPlayer(c);
                    if (sp == null) return;
                    CsState s = CsServer.state(sp);
                    s.money = 16000;
                    s.level = 10;
                    CsServer.save(sp, s);
                    ServerWorld w = sp.getEntityWorld();
                    Vec3d eye = sp.getEyePos(), look = Vec3d.fromPolar(0, sp.getYaw());
                    for (int i = 0; i < 3; i++) {
                        ZombieEntity zb = EntityType.ZOMBIE.create(w, SpawnReason.TRIGGERED);
                        if (zb == null) continue;
                        Vec3d at = eye.add(look.multiply(9 + i * 2)).add(look.z * (i - 1) * 2.5, 0, -look.x * (i - 1) * 2.5);
                        zb.refreshPositionAndAngles(at.x, w.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(at.x), (int) Math.floor(at.z)), at.z, sp.getYaw() + 180, 0);
                        zb.setAiDisabled(true);
                        zb.setPersistent();
                        w.spawnEntity(zb);
                    }
                    w.setTimeOfDay(6000);
                });
                next(21, 20);
            }
            case 21 -> {
                ClientCs.send("buy", "ak47");
                ClientCs.send("buy", "vesthelm");
                ClientCs.send("buy", "hegrenade");
                ClientCs.send("buy", "ammo:762nato");
                c.setScreen(new BuyScreen(Sheets.CATEGORIES.indexOf("rifles")));
                next(22, 15);
            }
            case 22 -> {
                shot(c, "7_buy_menu");
                c.setScreen(null);
                next(23, 5);
            }
            case 23 -> {
                ClientCs.select(CsState.SLOT_PRIMARY);
                next(24, 30);
            }
            case 24 -> {
                trigger = stageTicks < 30;
                if (stageTicks == 4) shot(c, "8_ak_spray");
                if (stageTicks > 50) { ClientCs.startReload(); next(25, 0); }
            }
            case 25 -> {
                if (stageTicks == 30) shot(c, "9_ak_reload");
                if (stageTicks > 80) { ClientCs.select(CsState.SLOT_KNIFE); next(26, 25); }
            }
            case 26 -> {
                trigger = stageTicks < 3;
                if (stageTicks == 5) shot(c, "10_knife_slash");
                if (stageTicks == 30) ClientCs.inspect();
                if (stageTicks == 48) shot(c, "10b_knife_inspect");
                if (stageTicks > 80) { ClientCs.select(CsState.SLOT_PRIMARY); next(27, 25); }
            }
            case 27 -> {
                if (stageTicks == 1) ClientCs.inspect();
                if (stageTicks == 18) { shot(c, "11_inspect"); CsHudCraft.LOG.info("[CSHudCraft] test: showcase done"); next(99, 0); }
            }
            default -> { }
        }
    }

    private static void report(String check, String failure) {
        if (!active || !reported.add(check)) return;
        String line = failure == null ? "pass " + check : "fail " + check + ": " + failure;
        CsHudCraft.LOG.info("[CSHudCraft] test: {}", line);
        try {
            Files.createDirectories(checksFile.toAbsolutePath().getParent());
            Files.writeString(checksFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            CsHudCraft.LOG.warn("[CSHudCraft] Could not write test check {}: {}", check, e.toString());
        }
    }
}
