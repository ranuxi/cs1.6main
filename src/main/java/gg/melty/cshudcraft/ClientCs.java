package gg.melty.cshudcraft;

import gg.melty.cshudcraft.mdl.MdlModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The CS side of the client: which weapon is out, its viewmodel animation and events, firing with
 * CS spread and view punch, reloads, the knife, grenades, zoom, inspect and quick ammo buys.
 * Predicts locally for instant feedback; the server (CsServer) decides and syncs CsState back.
 */
public final class ClientCs {
    public record Anim(String model, int sequence, float frame) {}
    public record Flash(int attachment, int sprite, float scale, float roll) {}

    private static final Random RNG = new Random();

    private static CsState synced;
    private static CsState local;

    // viewmodel animation
    private static String animModel;
    private static int animSeq = -1;
    private static double animStart;
    private static boolean animLoop;
    private static float lastEventFrame = -1;

    // weapon timing (ms)
    private static double drawUntil, nextShot, reloadUntil, inspectStart = -1, grenadeReady;
    /** 0 none, 1 clip reload, 2 shell start, 3 shell insert, 4 shell finish */
    private static int reloadStage;
    private static int shotsFired;
    private static double lastShotTime;
    private static boolean triggerWasDown, altWasDown, dryFired, knifeAlt;
    private static String pulling = "";
    private static int lastSlot = CsState.SLOT_KNIFE;
    private static int lastHotbar = -1;

    // view punch (degrees, CS punchangle) and what of it is already applied to the camera
    private static float punchPitch, punchYaw, appliedPitch, appliedYaw;
    private static int recoilDir = 1;
    private static double lastFrame;

    private static boolean zoomed;
    private static double rezoomAt;
    private static Flash flash;
    private static double flashUntil;
    private static float blind;
    private static double lastBlindFrame;
    private static boolean wasActive;

    private ClientCs() {}

    static double now() { return System.nanoTime() / 1e6; }

    public static CsState state() { return local; }

    /** CS mode is on, the HUD can draw, and a player is in a world: weapons own the mouse buttons. */
    public static boolean takesInput() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return CsHudCraft.csActive() && mc.player != null && !mc.player.isSpectator() && local != null;
    }

    // ------------------------------------------------------------------ state and sync

    private static void syncFromServer(ClientPlayerEntity p) {
        CsState s = p.getAttached(CsNet.STATE);
        if (s == null || s == synced) return;
        boolean first = synced == null;
        synced = s;
        int keepSlot = local != null ? local.slot : s.slot;
        local = s.copy();
        if (!first && local.hasSlot(keepSlot) && local.slot != keepSlot) local.slot = keepSlot;
        if (!local.hasSlot(local.slot)) local.slot = CsState.SLOT_KNIFE;
        if (first || animModel == null || !animModel.equals(modelFor(local))) deploy(false);
    }

    static void send(String action, String arg, float... nums) {
        List<Float> l = new ArrayList<>(nums.length);
        for (float f : nums) l.add(f);
        if (ClientPlayNetworking.canSend(CsNet.Action.ID)) ClientPlayNetworking.send(new CsNet.Action(action, arg, l));
    }

    // ------------------------------------------------------------------ per tick

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            synced = null;
            local = null;
            return;
        }
        syncFromServer(p);
        boolean active = takesInput();
        if (active != wasActive) {
            wasActive = active;
            zoomed = false;
            if (active) deploy(true);
        }
        if (!active) {
            triggerWasDown = altWasDown = false;
            lastHotbar = p.getInventory().getSelectedSlot();
            return;
        }
        double now = now();
        // mouse wheel moves Minecraft's hotbar: turn that into next/previous CS weapon
        int hb = p.getInventory().getSelectedSlot();
        if (lastHotbar >= 0 && hb != lastHotbar) {
            int dir = ((hb - lastHotbar + 9) % 9) <= 4 ? 1 : -1;
            p.getInventory().setSelectedSlot(lastHotbar);
            cycle(dir);
        }
        lastHotbar = p.getInventory().getSelectedSlot();

        while (CsHudCraft.RELOAD.wasPressed()) startReload();
        while (CsHudCraft.INSPECT.wasPressed()) inspect();
        while (CsHudCraft.FLASHLIGHT.wasPressed()) send("flashlight", "");
        while (CsHudCraft.BUY.wasPressed()) mc.setScreen(new BuyScreen());
        while (CsHudCraft.AMMO_PRIMARY.wasPressed()) send("buy", "ammo_primary");
        while (CsHudCraft.AMMO_SECONDARY.wasPressed()) send("buy", "ammo_secondary");

        if (rezoomAt > 0 && now >= rezoomAt) {
            rezoomAt = 0;
            Sheets.Weapon w = local.current();
            if (w != null && w.canZoom()) zoomed = true;
        }

        boolean trigger = (mc.currentScreen == null && mc.options.attackKey.isPressed()) || TestMode.trigger;
        boolean alt = (mc.currentScreen == null && mc.options.useKey.isPressed()) || TestMode.alt;
        // defusing takes the use key while looking at a planted C4 close by
        C4Entity bomb = alt ? bombInSight(mc, p) : null;
        if (bomb != null) {
            defuseInput(bomb, now);
            alt = false;
        } else if (defuseStart >= 0) {
            defuseStart = -1;
        }
        if (local.slot == CsState.SLOT_BOMB) bombInput(p, trigger, now);
        else if (local.slot == CsState.SLOT_GRENADE) grenadeInput(p, trigger, now);
        else if (local.slot == CsState.SLOT_KNIFE) knifeInput(p, trigger, alt, now);
        else {
            if (trigger) fire(p, now);
            if (alt && !altWasDown) altFire();
        }
        if (!trigger) dryFired = false;
        if (!trigger && now - lastShotTime > 260) shotsFired = 0;
        triggerWasDown = trigger;
        altWasDown = alt;
        reloadProgress(now);
        if (KillFeed.enemyDownPending) {
            KillFeed.enemyDownPending = false;
            CsSounds.play(CsHudCraft.ASSETS, CsSounds.ENEMY_DOWN, 0.8f);
        }
    }

    /** Slots 1-4 from the hotbar keys, 5 = previous weapon; drained here so Minecraft never sees them. */
    public static void consumeHotbarKeys(MinecraftClient mc) {
        for (int i = 0; i < mc.options.hotbarKeys.length; i++) {
            while (mc.options.hotbarKeys[i].wasPressed()) {
                if (i < 4) select(i + 1);
                else if (i == 4) select(local != null && local.hasBomb ? CsState.SLOT_BOMB : lastSlot);
            }
        }
    }

    // ------------------------------------------------------------------ switching

    public static void select(int slot) {
        if (local == null || !local.hasSlot(slot)) return;
        if (slot == local.slot) {
            if (slot == CsState.SLOT_GRENADE) { // pressing 4 again cycles grenade types, like CS
                List<String> held = local.grenades.entrySet().stream().filter(e -> e.getValue() > 0).map(java.util.Map.Entry::getKey).toList();
                if (held.size() > 1) {
                    int i = (held.indexOf(local.currentGrenade()) + 1) % held.size();
                    local.grenade = held.get(i);
                    send("select", local.grenade, slot);
                    deploy(true);
                }
            }
            return;
        }
        lastSlot = local.slot;
        local.slot = slot;
        if (slot == CsState.SLOT_GRENADE) local.grenade = local.currentGrenade();
        send("select", slot == CsState.SLOT_GRENADE ? local.grenade : "", slot);
        deploy(true);
    }

    private static void cycle(int dir) {
        if (local == null) return;
        int s = local.slot;
        for (int i = 0; i < 5; i++) {
            s = ((s - 1 + dir + 5) % 5) + 1;
            if (local.hasSlot(s)) { select(s); return; }
        }
    }

    static String modelFor(CsState s) {
        if (s.slot == CsState.SLOT_BOMB) return Sheets.EQUIPMENT.get("c4").model();
        if (s.slot == CsState.SLOT_GRENADE) {
            Sheets.Equipment g = Sheets.EQUIPMENT.get(s.currentGrenade());
            return g == null ? null : g.model();
        }
        Sheets.Weapon w = s.current();
        return w == null ? null : w.model();
    }

    /** Takes the current weapon out: draw animation, deploy sound, no firing until it is up. */
    static void deploy(boolean withSound) {
        if (local == null) return;
        zoomed = false;
        rezoomAt = 0;
        reloadStage = 0;
        inspectStart = -1;
        pulling = "";
        shotsFired = 0;
        String model = modelFor(local);
        animModel = model;
        if (model == null) return;
        plantStart = -1;
        String drawSeq, idle;
        if (local.slot == CsState.SLOT_BOMB) {
            Sheets.Equipment b = Sheets.EQUIPMENT.get("c4");
            drawSeq = b.seqDraw();
            idle = b.seqIdle();
        } else if (local.slot == CsState.SLOT_GRENADE) {
            Sheets.Equipment g = Sheets.EQUIPMENT.get(local.currentGrenade());
            drawSeq = g.seqDraw();
            idle = g.seqIdle();
        } else {
            Sheets.Weapon w = local.current();
            drawSeq = w.seqDraw();
            idle = w.seqIdle();
        }
        float dur = play(drawSeq, false);
        if (dur <= 0) play(idle, true);
        drawUntil = now() + Math.min(1000, dur * 1000 * 0.85);
        if (withSound && local.slot == CsState.SLOT_KNIFE) CsSounds.play(CsHudCraft.ASSETS, "weapons/knife_deploy1.wav", 0.8f);
    }

    // ------------------------------------------------------------------ animation

    /** Starts a sequence of the current model; returns its duration in seconds (0 if missing). */
    static float play(String seqName, boolean loop) {
        if (animModel == null || seqName == null || seqName.equals("-")) return 0;
        ViewModelRenderer.Loaded l = ViewModelRenderer.model(animModel);
        if (l == null) return 0;
        int seq = l.model().sequence(seqName);
        if (seq < 0) return 0;
        animSeq = seq;
        animStart = now();
        animLoop = loop;
        lastEventFrame = -1;
        return l.model().sequences.get(seq).duration();
    }

    private static String idleSeq() {
        if (local == null) return null;
        if (local.slot == CsState.SLOT_BOMB) return Sheets.EQUIPMENT.get("c4").seqIdle();
        if (local.slot == CsState.SLOT_GRENADE) {
            Sheets.Equipment g = Sheets.EQUIPMENT.get(local.currentGrenade());
            return g == null ? null : g.seqIdle();
        }
        Sheets.Weapon w = local.current();
        return w == null ? null : w.seqIdle();
    }

    /** The pose to draw this frame; runs the model's sound and muzzle-flash events as frames pass. */
    public static Anim viewAnim() {
        if (local == null || animModel == null) return null;
        ViewModelRenderer.Loaded l = ViewModelRenderer.model(animModel);
        if (l == null) return null;
        MdlModel m = l.model();
        if (animSeq < 0 || animSeq >= m.sequences.size()) {
            String idle = idleSeq();
            if (idle == null || play(idle, true) <= 0 && animSeq < 0) animSeq = 0;
        }
        MdlModel.Sequence s = m.sequences.get(animSeq);
        float frame = (float) ((now() - animStart) / 1000.0 * Math.max(1f, s.fps()));
        int last = Math.max(0, s.numFrames() - 1);
        if (frame >= last) {
            if (animLoop && last > 0) {
                frame = frame % last;
                lastEventFrame = -1;
            } else if (!animLoop) {
                runEvents(s, last + 0.5f);
                String idle = idleSeq();
                if (idle != null && play(idle, true) > 0) return viewAnim();
                frame = last;
            }
        }
        runEvents(s, frame);
        return new Anim(animModel, animSeq, frame);
    }

    private static void runEvents(MdlModel.Sequence s, float frame) {
        for (MdlModel.Event e : s.events()) {
            if (e.frame() > lastEventFrame && e.frame() <= frame) {
                switch (e.event()) {
                    case 5004 -> CsSounds.play(CsHudCraft.ASSETS, e.options(), 0.9f);
                    case 5001, 5011, 5021, 5031 -> {
                        int type = parseInt(e.options(), 11);
                        float scale = (type / 10) * 0.1f;
                        flash = new Flash((e.event() - 5001) / 10, type % 10, scale <= 0 ? 0.5f : scale, RNG.nextFloat() * 360f);
                        flashUntil = now() + (TestMode.shotsMode() ? 400 : 45); // held longer only for dev screenshots
                    }
                    default -> { }
                }
            }
        }
        lastEventFrame = frame;
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    public static Flash muzzleFlash() { return now() < flashUntil ? flash : null; }

    /** Inspect (CS 1.6 has no inspect animation, so the weapon is turned toward you and back) and draw bob. */
    public static void applyViewModelMotion(MatrixStack m, float tickProgress) {
        if (inspectStart < 0 || boneInspectRow() != null) return;
        double t = (now() - inspectStart) / 2200.0;
        if (t >= 1) { inspectStart = -1; return; }
        // ease in, hold, ease out; the weapon turns about its own grip, so the hands stay in view
        float k = (float) (t < 0.25 ? Math.sin(t / 0.25 * Math.PI / 2) : t > 0.8 ? Math.cos((t - 0.8) / 0.2 * Math.PI / 2) : 1);
        float sway = (float) Math.sin((t - 0.25) * Math.PI * 2) * (t > 0.25 && t < 0.8 ? 1 : 0);
        float px = 0.10f, py = -0.22f, pz = -0.45f; // grip of a CS viewmodel, in view space
        m.translate(px, py, pz);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-28f * k - 6f * sway));
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(18f * k));
        m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(6f * k + 4f * sway));
        m.translate(-px, -py + 0.03f * k, -pz);
    }

    /** inspect.json row for the weapon in hand, when its inspect turns hand bones. */
    static Sheets.Inspect boneInspectRow() {
        if (local == null) return null;
        Sheets.Weapon w = local.current();
        if (w == null || local.slot == CsState.SLOT_GRENADE) return null;
        Sheets.Inspect row = Sheets.INSPECT.get(w.id());
        return row != null && row.mode().equals("bones") ? row : null;
    }

    /** The bone turn of a bones-mode inspect at this moment: {ax, ay, az, lift}, or null. */
    public static float[] boneInspect() {
        Sheets.Inspect row = boneInspectRow();
        if (inspectStart < 0 || row == null) return null;
        double t = (now() - inspectStart) / 2200.0;
        if (t >= 1) { inspectStart = -1; return null; }
        float k = (float) (t < 0.25 ? Math.sin(t / 0.25 * Math.PI / 2) : t > 0.8 ? Math.cos((t - 0.8) / 0.2 * Math.PI / 2) : 1);
        float rock = (float) Math.sin((t - 0.25) / 0.55 * Math.PI * 2) * (t > 0.25 && t < 0.8 ? 1 : 0);
        return new float[]{row.ax() * k + 8f * rock * k, row.ay() * k, row.az() * k - 12f * rock * k, row.lift() * k};
    }

    static void inspect() {
        if (local == null || reloadStage != 0 || now() < drawUntil) return;
        inspectStart = now();
        String idle = idleSeq();
        if (idle != null) play(idle, true);
    }

    // ------------------------------------------------------------------ guns

    private static void fire(ClientPlayerEntity p, double now) {
        Sheets.Weapon w = local.current();
        if (w == null || w.isKnife()) return;
        if (now < drawUntil) return;
        if (reloadStage == 2 || reloadStage == 3) reloadStage = 0; // shotguns stop loading shells to fire
        if (reloadStage != 0) return;
        if (now < nextShot) return;
        if (!w.auto() && triggerWasDown) return;
        int clip = local.clip(w.id());
        if (clip <= 0) {
            if (!dryFired) {
                dryFired = true;
                CsSounds.play(CsHudCraft.ASSETS, w.slot() == CsState.SLOT_PISTOL ? "weapons/dryfire_pistol.wav" : "weapons/dryfire_rifle.wav", 0.8f);
                if (local.reserveOf(w.ammo()) > 0) startReload();
            }
            return;
        }
        inspectStart = -1;
        nextShot = (now - nextShot) < w.cycle() * 1000 ? nextShot + w.cycle() * 1000 : now + w.cycle() * 1000;

        // CS spread: by stance, growing with each shot of a burst
        double speed = p.getVelocity().horizontalLength() * 20.0; // blocks per second
        boolean air = !p.isOnGround();
        boolean moving = speed > 2.4;
        double spread = air ? w.spreadAir() : moving ? w.spreadMove() : w.spreadBase();
        if (zoomed && w.zoomSpread() > 0 && !air && !moving) spread = w.zoomSpread();
        if (p.isSneaking() && !air) spread *= 0.8;
        spread *= Math.min(w.spreadMaxMul(), 1 + shotsFired * w.spreadGrow());

        Vec3d fwd = p.getRotationVec(1f);
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vec3d(1, 0, 0);
        right = right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();
        float[] dirs = new float[w.pellets() * 3];
        Vec3d first = fwd;
        for (int i = 0; i < w.pellets(); i++) {
            double x = (RNG.nextDouble() + RNG.nextDouble() - 1) * spread;
            double y = (RNG.nextDouble() + RNG.nextDouble() - 1) * spread;
            Vec3d d = fwd.add(right.multiply(x)).add(up.multiply(y)).normalize();
            if (i == 0) first = d;
            dirs[i * 3] = (float) d.x;
            dirs[i * 3 + 1] = (float) d.y;
            dirs[i * 3 + 2] = (float) d.z;
            effects(p, d, w, i == 0);
        }
        send("fire", w.id(), dirs);
        local.clips.put(w.id(), clip - 1);
        shotsFired++;
        lastShotTime = now;
        CsSounds.play(CsHudCraft.ASSETS, w.fireSound(), 1f);
        String seq = (clip == 1 && !w.seqShootEmpty().equals("-")) ? w.seqShootEmpty() : w.seqShoot().get(RNG.nextInt(w.seqShoot().size()));
        play(seq, false);
        kick(w);
        if (zoomed && w.cycle() > 1.0) { // bolt-action rifles come out of the scope to cycle the bolt
            zoomed = false;
            rezoomAt = now + w.cycle() * 1000 * 0.9;
        }
        CsHudCraft.onShot(first);
    }

    /** CS KickBack: view punch up, wandering sideways; it decays back each frame. */
    private static void kick(Sheets.Weapon w) {
        float up = Math.min(w.recoilUpMax(), w.recoilUp() + (shotsFired - 1) * w.recoilUpGrow());
        punchPitch = Math.max(-w.recoilUpMax(), punchPitch - up);
        if (RNG.nextFloat() < 0.15f) recoilDir = -recoilDir;
        punchYaw += recoilDir * w.recoilSide() * (0.3f + RNG.nextFloat() * 0.7f);
        punchYaw = MathHelper.clamp(punchYaw, -w.recoilSide() * 3, w.recoilSide() * 3);
    }

    /** Called every frame: CS DropPunchAngle, applied to the real view so the bullets follow it. */
    public static void frame(MinecraftClient mc) {
        double now = now();
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1000.0);
        lastFrame = now;
        if (blind > 0) blind = (float) Math.max(0, blind - dt / 3.0);
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        float len = (float) Math.sqrt(punchPitch * punchPitch + punchYaw * punchYaw);
        if (len > 0) {
            float nl = (float) Math.max(0, len - (10.0 + len * 0.5) * dt);
            punchPitch *= nl / len;
            punchYaw *= nl / len;
        }
        float dp = punchPitch - appliedPitch, dy = punchYaw - appliedYaw;
        if (dp != 0 || dy != 0) {
            p.changeLookDirection(dy / 0.15, dp / 0.15);
            appliedPitch = punchPitch;
            appliedYaw = punchYaw;
        }
    }

    /** What the shooter sees: impact dust, blood, ricochet and hit sounds, tracers on every other shot. */
    private static void effects(ClientPlayerEntity p, Vec3d dir, Sheets.Weapon w, boolean withSound) {
        var world = p.getEntityWorld();
        Vec3d eye = p.getEyePos();
        Vec3d end = eye.add(dir.multiply(w.rangeBlocks()));
        BlockHitResult bh = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        Vec3d stop = bh.getType() == HitResult.Type.MISS ? end : bh.getPos();
        EntityHitResult eh = ProjectileUtil.raycast(p, eye, stop, new Box(eye, stop).expand(1.0),
                e -> e instanceof LivingEntity && e.isAlive() && e != p, eye.squaredDistanceTo(stop));
        if (eh != null) stop = eh.getPos();
        if ((shotsFired & 1) == 0) {
            double dist = Math.min(eye.distanceTo(stop), 60);
            for (double d = 1.5; d < dist; d += 1.2) {
                Vec3d at = eye.add(dir.multiply(d)).add(0, -0.08, 0);
                world.addParticleClient(new DustParticleEffect(0xFFE27A, 0.35f), at.x, at.y, at.z, 0, 0, 0);
            }
        }
        if (!withSound) return;
        if (eh != null) {
            CsSounds.play(CsHudCraft.ASSETS, "player/bhit_flesh-" + (1 + RNG.nextInt(3)) + ".wav", 0.7f);
        } else if (bh.getType() == HitResult.Type.BLOCK && RNG.nextInt(3) == 0) {
            CsSounds.play(CsHudCraft.ASSETS, "weapons/ric_conc-" + (1 + RNG.nextInt(2)) + ".wav", 0.35f);
            world.addParticleClient(ParticleTypes.SMOKE, stop.x, stop.y, stop.z, 0, 0.02, 0);
        }
    }

    static void altFire() {
        Sheets.Weapon w = local.current();
        if (w == null || !w.canZoom() || reloadStage != 0) return;
        zoomed = !zoomed;
        rezoomAt = 0;
        CsSounds.play(CsHudCraft.ASSETS, "weapons/zoom.wav", 0.7f);
    }

    public static float zoomFov() {
        if (!takesInput() || !zoomed) return 0;
        Sheets.Weapon w = local.current();
        if (w == null || !w.canZoom()) return 0;
        // CS fov is horizontal at 4:3; Minecraft's is vertical
        return (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(w.zoomFov()) / 2) * 0.75));
    }

    /** Sniper rifles show the CS scope and hide the weapon; AUG/SG552 just zoom. */
    public static boolean scoped() {
        if (!takesInput() || !zoomed) return false;
        Sheets.Weapon w = local.current();
        return w != null && w.category().equals("snipers");
    }

    // ------------------------------------------------------------------ reload

    static void startReload() {
        Sheets.Weapon w = local == null ? null : local.current();
        if (w == null || w.isKnife() || reloadStage != 0 || now() < drawUntil) return;
        if (local.clip(w.id()) >= w.clip() || local.reserveOf(w.ammo()) <= 0) return;
        zoomed = false;
        inspectStart = -1;
        send("reload_start", w.id());
        if (w.shellReload()) {
            reloadStage = 2;
            reloadUntil = now() + play("start_reload", false) * 1000;
        } else {
            reloadStage = 1;
            reloadUntil = now() + Math.max(0.5f, play(w.seqReload(), false)) * 1000;
        }
    }

    private static void reloadProgress(double now) {
        if (reloadStage == 0 || now < reloadUntil) return;
        Sheets.Weapon w = local.current();
        if (w == null) { reloadStage = 0; return; }
        switch (reloadStage) {
            case 1 -> {
                int n = Math.min(w.clip() - local.clip(w.id()), local.reserveOf(w.ammo()));
                local.clips.put(w.id(), local.clip(w.id()) + n);
                local.reserve.put(w.ammo(), local.reserveOf(w.ammo()) - n);
                send("reload_finish", w.id());
                reloadStage = 0;
            }
            case 2 -> {
                reloadStage = 3;
                reloadUntil = now + Math.max(0.25f, play("insert", false)) * 1000;
            }
            case 3 -> {
                local.clips.put(w.id(), local.clip(w.id()) + 1);
                local.reserve.put(w.ammo(), local.reserveOf(w.ammo()) - 1);
                send("reload_finish", w.id(), 1);
                if (local.clip(w.id()) >= w.clip() || local.reserveOf(w.ammo()) <= 0) {
                    reloadStage = 4;
                    reloadUntil = now + play("after_reload", false) * 1000;
                } else {
                    reloadUntil = now + Math.max(0.25f, play("insert", false)) * 1000;
                }
            }
            default -> reloadStage = 0;
        }
    }

    public static boolean reloading() { return reloadStage != 0; }

    // ------------------------------------------------------------------ knife

    private static void knifeInput(ClientPlayerEntity p, boolean trigger, boolean alt, double now) {
        if (now < drawUntil || now < nextShot) return;
        if (!trigger && !alt) return;
        boolean stab = alt && !trigger;
        inspectStart = -1;
        Vec3d dir = p.getRotationVec(1f);
        boolean hit = knifeTrace(p, dir, stab ? 2.0 : 2.5);
        boolean wall = !hit && knifeWall(p, dir, stab ? 2.0 : 2.5);
        if (stab) {
            nextShot = now + (hit ? 1100 : 1000);
            play(hit ? "stab" : "stab_miss", false);
            CsSounds.play(CsHudCraft.ASSETS, hit ? "weapons/knife_stab.wav" : wall ? "weapons/knife_hitwall1.wav" : "weapons/knife_slash1.wav", 0.9f);
        } else {
            nextShot = now + (hit ? 400 : 350);
            knifeAlt = !knifeAlt;
            play(knifeAlt ? "midslash1" : "midslash2", false);
            CsSounds.play(CsHudCraft.ASSETS, hit ? "weapons/knife_hit" + (1 + RNG.nextInt(4)) + ".wav"
                    : wall ? "weapons/knife_hitwall1.wav" : "weapons/knife_slash" + (1 + RNG.nextInt(2)) + ".wav", 0.9f);
        }
        send("knife", stab ? "stab" : "slash", (float) dir.x, (float) dir.y, (float) dir.z);
    }

    private static boolean knifeTrace(ClientPlayerEntity p, Vec3d dir, double range) {
        Vec3d eye = p.getEyePos(), end = eye.add(dir.multiply(range));
        BlockHitResult bh = p.getEntityWorld().raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        Vec3d stop = bh.getType() == HitResult.Type.MISS ? end : bh.getPos();
        return ProjectileUtil.raycast(p, eye, stop, new Box(eye, stop).expand(0.6),
                e -> e instanceof LivingEntity && e.isAlive() && e != p, eye.squaredDistanceTo(stop)) != null;
    }

    private static boolean knifeWall(ClientPlayerEntity p, Vec3d dir, double range) {
        Vec3d eye = p.getEyePos();
        return p.getEntityWorld().raycast(new RaycastContext(eye, eye.add(dir.multiply(range)), RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, p)).getType() == HitResult.Type.BLOCK;
    }

    // ------------------------------------------------------------------ grenades

    private static void grenadeInput(ClientPlayerEntity p, boolean trigger, double now) {
        String g = local.currentGrenade();
        Sheets.Equipment e = Sheets.EQUIPMENT.get(g);
        if (e == null || now < drawUntil) return;
        if (trigger && pulling.isEmpty() && now >= nextShot) {
            pulling = g;
            grenadeReady = now + play(e.seqPull(), false) * 1000;
        } else if (!trigger && !pulling.isEmpty() && now >= grenadeReady) {
            Vec3d dir = p.getRotationVec(1f);
            send("throw", pulling, (float) dir.x, (float) dir.y, (float) dir.z);
            float d = play(e.seqThrow(), false);
            local.grenades.merge(pulling, -1, Integer::sum);
            if (local.grenades.getOrDefault(pulling, 0) <= 0) local.grenades.remove(pulling);
            pulling = "";
            nextShot = now + d * 1000 + 100;
            if (!local.hasSlot(CsState.SLOT_GRENADE)) {
                int back = local.hasSlot(lastSlot) && lastSlot != CsState.SLOT_GRENADE ? lastSlot : CsState.SLOT_KNIFE;
                select(back);
            } else {
                drawUntil = nextShot;
            }
        }
    }

    // ------------------------------------------------------------------ C4

    private static double plantStart = -1, defuseStart = -1;
    private static int defuseId = -1;
    static final double PLANT_MS = 3030, DEFUSE_MS = 10000;

    /** Hold fire on the ground for the whole pressbutton animation; letting go or moving cancels. */
    private static void bombInput(ClientPlayerEntity p, boolean trigger, double now) {
        if (now < drawUntil) return;
        boolean still = p.isOnGround() && p.getVelocity().horizontalLength() < 0.05;
        if (trigger && still) {
            if (plantStart < 0) {
                plantStart = now;
                play(Sheets.EQUIPMENT.get("c4").seqPull(), false);
                send("plant_start", "c4");
            } else if (now - plantStart >= PLANT_MS) {
                plantStart = -1;
                send("plant", "c4");
                local.hasBomb = false;
                int back = local.hasSlot(CsState.SLOT_PRIMARY) ? CsState.SLOT_PRIMARY : local.hasSlot(CsState.SLOT_PISTOL) ? CsState.SLOT_PISTOL : CsState.SLOT_KNIFE;
                select(back);
            }
        } else if (plantStart >= 0) {
            plantStart = -1;
            play(Sheets.EQUIPMENT.get("c4").seqIdle(), true);
        }
    }

    private static C4Entity bombInSight(MinecraftClient mc, ClientPlayerEntity p) {
        if (mc.crosshairTarget instanceof EntityHitResult eh && eh.getEntity() instanceof C4Entity b
                && p.getEyePos().distanceTo(b.getEntityPos()) <= 2.8) return b;
        return null;
    }

    private static void defuseInput(C4Entity bomb, double now) {
        if (defuseStart < 0 || defuseId != bomb.getId()) {
            defuseStart = now;
            defuseId = bomb.getId();
            send("defuse_start", "c4", bomb.getId());
        } else if (now - defuseStart >= DEFUSE_MS) {
            send("defuse_finish", "c4", bomb.getId());
            defuseStart = -1;
        }
    }

    /** Plant or defuse progress for the HUD bar: {fraction, 0 = plant / 1 = defuse}, or null. */
    public static float[] actionProgress() {
        double now = now();
        if (plantStart >= 0) return new float[]{(float) Math.min(1, (now - plantStart) / PLANT_MS), 0};
        if (defuseStart >= 0) return new float[]{(float) Math.min(1, (now - defuseStart) / DEFUSE_MS), 1};
        return null;
    }

    // ------------------------------------------------------------------ server events

    public static void onEvent(CsNet.Event ev) {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<Float> n = ev.nums();
        switch (ev.kind()) {
            case "sound" -> {
                MpTest.onSound(ev.arg());
                float vol = 1f;
                if (n.size() >= 4 && mc.player != null) {
                    double d = mc.player.getEyePos().distanceTo(new Vec3d(n.get(0), n.get(1), n.get(2)));
                    vol = n.get(3) * (float) Math.pow(Math.max(0, 1 - d / 64.0), 1.5);
                }
                if (vol > 0.02f) CsSounds.play(CsHudCraft.ASSETS, ev.arg(), vol);
            }
            case "sound_ch" -> {
                int bar = ev.arg().indexOf('|');
                if (bar < 0) return;
                MpTest.onSound(ev.arg().substring(bar + 1));
                float vol = 1f;
                if (n.size() >= 4 && mc.player != null) {
                    double d = mc.player.getEyePos().distanceTo(new Vec3d(n.get(0), n.get(1), n.get(2)));
                    vol = n.get(3) * (float) Math.pow(Math.max(0, 1 - d / 64.0), 1.5);
                }
                if (vol > 0.02f) CsSounds.playOn(CsHudCraft.ASSETS, ev.arg().substring(0, bar), ev.arg().substring(bar + 1), vol);
            }
            case "flash" -> blind = Math.max(blind, n.isEmpty() ? 1f : n.get(0));
            case "award" -> CsHud.onAward(ev.arg(), n.size() > 0 ? Math.round(n.get(0)) : 0, n.size() > 1 ? Math.round(n.get(1)) : 0,
                    n.size() > 2 ? Math.round(n.get(2)) : 0, n.size() > 3 ? Math.round(n.get(3)) : 1);
            case "hit" -> {
                if (!n.isEmpty() && n.get(0) > 0) CsSounds.play(CsHudCraft.ASSETS, "weapons/headshot2.wav", 0.6f);
            }
            case "kill" -> KillFeed.onEvent(ev.arg(), n);
            case "denied" -> {
                if (mc.player != null) mc.player.sendMessage(Text.translatable("cshudcraft.denied." + ev.arg()).formatted(Formatting.RED), true);
                CsSounds.play(CsHudCraft.ASSETS, "common/wpn_denyselect.wav", 0.8f);
            }
            default -> { }
        }
    }

    public static float blindness() { return blind; }

    public static void onModeChanged(boolean cs) {
        send("mode", cs ? "cs" : "mc");
        zoomed = false;
        if (!cs) {
            reloadStage = 0;
            pulling = "";
        }
    }

    // ------------------------------------------------------------------ HUD helpers

    /** Current spread in CS units for the crosshair gap (moving / air widen it). */
    public static float crosshairSpread(ClientPlayerEntity p) {
        Sheets.Weapon w = local == null ? null : local.current();
        float gap = w == null ? 4 : w.crosshairGap();
        double speed = p.getVelocity().horizontalLength() * 20.0;
        if (!p.isOnGround()) gap *= 2.2f;
        else if (speed > 2.4) gap *= 1.6f;
        if (p.isSneaking()) gap *= 0.8f;
        if (w != null && !w.isKnife()) gap += Math.min(12, shotsFired * 1.5f);
        return gap;
    }
}
