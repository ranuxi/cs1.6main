package gg.melty.cshudcraft;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

/**
 * Draws Counter-Strike 1.6's HUD (sheets/hud_elements.json, status_icons.json, killfeed_icons.json)
 * with the sprites read from the player's own CS install: CS health, kevlar, money, the weapon's clip
 * and reserve with its ammo icon, XP/level, the sniper scope and flashbang white-out.
 * Layout works in CS's 640x480 space: one "virtual" pixel is guiHeight/480 GUI pixels.
 */
public final class CsHud {
    // sheets/hud_elements.json "colors"
    static final int[] HUD = {255, 160, 0};
    static final int[] RED = {250, 0, 0};
    static final int[] GREEN = {0, 160, 0};
    static final int[] FEED_ICON = {255, 80, 0}; // CS death-notice icon colour
    static final int CROSSHAIR_ARGB = 0xC832FA32; // 50,250,50 at CS's 200 alpha
    static final int CT_COLOR = 0xFF99CCFF, T_COLOR = 0xFFFF3F3F, NEUTRAL_COLOR = 0xFFD0D0D0;

    static final float MIN_ALPHA = 0.55f;
    static final float RADAR_RANGE = 32f;

    private final CsAssets assets;
    private final KillFeed feed;
    private float s;            // GUI pixels per CS pixel
    private float vw, vh;       // screen in CS pixels
    private float crosshairGap = 4f;
    private float fade = 1f;    // switch-in fade of the whole CS HUD
    private int lastHealth = -1, lastArmor = -1, lastMoney = -1, lastClip = -1;
    private float healthFade, armorFade, moneyFade, ammoFade;
    private long lastFrameNanos;
    private static int moneyDelta;
    private static long moneyDeltaAt;
    private static String levelUpText;
    private static long levelUpAt;
    private static String lastWeapon = "";
    private static long weaponShownAt;

    public CsHud(CsAssets assets, KillFeed feed) {
        this.assets = assets;
        this.feed = feed;
    }

    /** Called when switching into CS mode: the HUD fades in instead of popping. */
    public void fadeIn() { fade = 0f; }

    /** Server award: CS shows the money change next to the money; level-ups get a line of their own. */
    static void onAward(String reason, int money, int xp, int levelsGained, int level) {
        if (money != 0) {
            moneyDelta = money;
            moneyDeltaAt = System.currentTimeMillis();
        }
        if (levelsGained > 0) {
            levelUpText = Text.translatable("cshudcraft.hud.levelup", level).getString();
            levelUpAt = System.currentTimeMillis();
            CsSounds.play(CsHudCraft.ASSETS, "radio/letsgo.wav", 0.8f);
        }
    }

    public void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        CsState st = ClientCs.state();
        if (p == null || mc.options.hudHidden || !assets.loaded() || st == null) return;
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0 : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        fade = Math.min(1f, fade + dt * 3.5f);
        float f = fade;

        int gw = ctx.getScaledWindowWidth(), gh = ctx.getScaledWindowHeight();
        s = gh / 480f;
        vw = gw / s;
        vh = 480f;
        int fw = assets.region("number_0").w(), fh = assets.region("number_0").h();

        if (ClientCs.scoped()) scope(ctx, gw, gh);

        // health
        int health = MathHelper.ceil(p.getHealth() / p.getMaxHealth() * 100f);
        if (health != lastHealth) { healthFade = 1f; lastHealth = health; }
        healthFade = Math.max(0, healthFade - dt);
        int[] hc = health <= 25 ? RED : HUD;
        float y = vh - fh - fh / 2f;
        float x = fw / 2f;
        float ha = alpha(healthFade) * f;
        sprite(ctx, "cross", x, y, hc, ha);
        x += assets.region("cross").w() + fw / 2f;
        x = number(ctx, x, y, health, 3, hc, ha);

        // CS kevlar (bought armor), not Minecraft armor
        int armor = st.armor;
        if (armor != lastArmor) { armorFade = 1f; lastArmor = armor; }
        armorFade = Math.max(0, armorFade - dt);
        x += fw;
        String suit = armor == 0 ? "suit_empty" : st.helmet ? "suithelmet_full" : "suit_full";
        float aa = alpha(armorFade) * f;
        sprite(ctx, suit, x, y, HUD, aa);
        x += assets.region(suit).w() + fw / 2f;
        number(ctx, x, y, armor, 3, HUD, aa);

        // ammo: clip | reserve + the ammo type's icon; grenades show their count
        Sheets.Weapon w = st.current();
        int clip = -1, reserve = 0;
        CsAssets.Region ammoIcon = null;
        if (st.slot == CsState.SLOT_GRENADE) {
            String g = st.currentGrenade();
            if (!g.isEmpty()) {
                clip = st.grenades.getOrDefault(g, 0);
                ammoIcon = assets.weaponRegion(Sheets.EQUIPMENT.get(g).hud(), "ammo");
            }
        } else if (w != null && !w.isKnife()) {
            clip = st.clip(w.id());
            reserve = st.reserveOf(w.ammo());
            ammoIcon = assets.weaponRegion(Sheets.AMMO.get(w.ammo()).icon(), "ammo");
        }
        if (clip >= 0) {
            if (clip != lastClip) { ammoFade = 1f; lastClip = clip; }
            ammoFade = Math.max(0, ammoFade - dt);
            int[] col = (w != null && st.slot != CsState.SLOT_GRENADE && clip <= Math.max(1, w.clip() / 5)) ? RED : HUD;
            float am = alpha(ammoFade) * f;
            CsAssets.Region div = assets.region("divider");
            float iconW = ammoIcon != null ? ammoIcon.w() + fw / 2f : 0;
            float width = st.slot == CsState.SLOT_GRENADE ? digits(clip) * fw + iconW
                    : digits(clip) * fw + fw / 2f + div.w() + fw / 2f + digits(reserve) * fw + iconW;
            float ax = vw - fw / 2f - width;
            ax = number(ctx, ax, y, clip, 1, col, am);
            if (st.slot != CsState.SLOT_GRENADE) {
                ax += fw / 2f;
                sprite(ctx, "divider", ax, y - (div.h() - fh) / 2f, HUD, am);
                ax += div.w() + fw / 2f;
                ax = number(ctx, ax, y, reserve, 1, HUD, am);
            }
            if (ammoIcon != null) region(ctx, ammoIcon, ax + fw / 2f, y - (ammoIcon.h() - fh) / 2f, HUD, am);
        }

        // money, with CS's +$/-$ flash under it
        int money = st.money;
        if (lastMoney >= 0 && money != lastMoney && System.currentTimeMillis() - moneyDeltaAt > 300) {
            moneyDelta = money - lastMoney;
            moneyDeltaAt = System.currentTimeMillis();
        }
        if (money != lastMoney) { moneyFade = 1f; lastMoney = money; }
        moneyFade = Math.max(0, moneyFade - dt);
        float my = y - fh - fh / 2f;
        float mw = assets.region("dollar").w() + digits(money) * fw;
        float mx = vw - fw / 2f - mw;
        float ma = alpha(moneyFade) * f;
        int[] mc2 = System.currentTimeMillis() - moneyDeltaAt < 2000 ? (moneyDelta >= 0 ? GREEN : RED) : HUD;
        sprite(ctx, "dollar", mx, my, mc2, ma);
        number(ctx, mx + assets.region("dollar").w(), my, money, 1, mc2, ma);
        if (System.currentTimeMillis() - moneyDeltaAt < 2000 && moneyDelta != 0) {

            float dx = mx + assets.region("dollar").w();
            sprite(ctx, moneyDelta > 0 ? "plus" : "minus", dx - assets.region("plus").w() - fw, my - fh - 4, moneyDelta > 0 ? GREEN : RED, ma);
            number(ctx, dx, my - fh - 4, Math.abs(moneyDelta), 1, moneyDelta > 0 ? GREEN : RED, ma);
        }

        // weapon icon for a moment after switching (CS weapon selection)
        String wid = st.slot == CsState.SLOT_GRENADE ? st.currentGrenade() : (w == null ? "" : w.id());
        if (!wid.equals(lastWeapon)) { lastWeapon = wid; weaponShownAt = System.currentTimeMillis(); }
        if (System.currentTimeMillis() - weaponShownAt < 1500 && !wid.isEmpty()) {
            String hud = st.slot == CsState.SLOT_GRENADE ? Sheets.EQUIPMENT.get(wid).hud() : w.hud();
            CsAssets.Region icon = assets.weaponRegion(hud, "weapon_s");
            if (icon != null) region(ctx, icon, vw - icon.w() - fw / 2f, my - fh * 2.6f - icon.h(), HUD, f);
        }

        // round timer: time until dusk (day) or dawn (night), above the hotbar position
        long tod = Math.floorMod(p.getEntityWorld().getTimeOfDay(), 24000L);
        long ticksLeft = tod < 12000 ? 12000 - tod : 24000 - tod;
        int secs = (int) (ticksLeft / 20);
        int[] tc = secs <= 30 ? RED : HUD;
        float ty = vh - fh * 2 - 6;
        CsAssets.Region sw = assets.region("stopwatch");
        float tw = sw.w() + fw / 2f + digits(secs / 60) * fw + fw / 2f + 2 * fw;
        float tx = vw / 2f - tw / 2f;
        sprite(ctx, "stopwatch", tx, ty, tc, MIN_ALPHA * f);
        tx += sw.w() + fw / 2f;
        tx = number(ctx, tx, ty, secs / 60, 1, tc, MIN_ALPHA * f);
        colon(ctx, tx + fw / 4f, ty, fh, tc, MIN_ALPHA * f);
        tx += fw / 2f;
        tx = number(ctx, tx, ty, (secs % 60) / 10, 1, tc, MIN_ALPHA * f);
        number(ctx, tx, ty, secs % 10, 1, tc, MIN_ALPHA * f);

        // XP and level, a thin bar under the timer
        int need = Sheets.xpForNextLevel(st.level);
        boolean max = st.level >= Sheets.eco("maxLevel");
        int bx = Math.round((vw / 2f - 90) * s), by = Math.round((vh - 10) * s), bw = Math.round(180 * s), bh = Math.max(2, Math.round(3 * s));
        int a8 = Math.round(f * 255);
        ctx.fill(bx, by, bx + bw, by + bh, (Math.round(f * 0x80) << 24));
        int filled = max ? bw : Math.round(bw * Math.min(1f, st.xp / (float) Math.max(1, need)));
        ctx.fill(bx, by, bx + filled, by + bh, (a8 << 24) | 0xFFA000);
        String lvl = Text.translatable("cshudcraft.hud.level", st.level, max ? "MAX" : st.xp + "/" + need).getString();
        ctx.drawTextWithShadow(mc.textRenderer, lvl, bx + bw / 2 - mc.textRenderer.getWidth(lvl) / 2, by - 10, (a8 << 24) | 0xFFB400);
        if (levelUpText != null && System.currentTimeMillis() - levelUpAt < 3000) {
            ctx.drawCenteredTextWithShadow(mc.textRenderer, levelUpText, gw / 2, gh / 3, 0xFF60FF60);
        }

        // flashlight battery, with the beam while it is on
        CsAssets.Region fe = assets.region("flash_empty");
        float fx = vw - fe.w() - fw / 2f, fy = fh / 2f;
        sprite(ctx, "flash_empty", fx, fy, HUD, MIN_ALPHA * f);
        int full = Math.round(fe.w() * MathHelper.clamp(st.battery, 0, 100) / 100f);
        if (full > 0) spritePart(ctx, "flash_full", fx + fe.w() - full, fy, fe.w() - full, full, HUD, MIN_ALPHA * f);
        if (st.flashlight) sprite(ctx, "flash_beam", fx - assets.region("flash_beam").w(), fy, HUD, MIN_ALPHA * f);

        // carrying the C4: CS shows its icon on the left, green
        float iy = vh / 2f;
        if (st.hasBomb && assets.region("c4") != null) {
            sprite(ctx, "c4", fw / 2f, iy - assets.region("c4").h() - 8, GREEN, 0.95f * f);
        }
        // plant / defuse progress bar, CS style, above the round timer
        float[] prog = ClientCs.actionProgress();
        if (prog != null) {
            int pw = Math.round(220 * s), ph = Math.max(4, Math.round(10 * s));
            int px = gw / 2 - pw / 2, py = Math.round((vh - fh * 3.6f) * s);
            ctx.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFFFFA000);
            ctx.fill(px, py, px + pw, py + ph, 0xC0000000);
            ctx.fill(px, py, px + Math.round(pw * prog[0]), py + ph, 0xFFFFA000);
            String label = Text.translatable(prog[1] == 0 ? "cshudcraft.hud.planting" : "cshudcraft.hud.defusing").getString();
            ctx.drawCenteredTextWithShadow(mc.textRenderer, label, gw / 2, py - 11, 0xFFFFB400);
        }
        // CS damage-type icons, left middle
        for (String icon : statusIcons(p)) {
            sprite(ctx, icon, fw / 2f, iy, HUD, 0.85f * f);
            iy += assets.region(icon).h() + 6;
        }

        radar(ctx, p);
        if (!ClientCs.scoped()) crosshair(ctx, mc, p);
        killFeed(ctx, mc.textRenderer, fy + assets.region("flash_empty").h() + 12);

        float blind = ClientCs.blindness();
        if (blind > 0) ctx.fill(0, 0, gw, gh, (Math.round(Math.min(1f, blind * 1.3f) * 255) << 24) | 0xFFFFFF);
        CsHudCraft.onCsHudFrame();
    }

    /** CS 1.6 sniper scope: the four scope_arc quarters from the install, black outside, thin black cross. */
    void scope(DrawContext ctx, int gw, int gh) {
        int size = gh;
        int x0 = gw / 2 - size / 2, y0 = 0, half = size / 2;
        String[] parts = {"sprites/scope_arc_nw.tga", "sprites/scope_arc_ne.tga", "sprites/scope_arc_sw.tga", "sprites/scope_arc.tga"};
        int[][] at = {{x0, y0}, {x0 + half, y0}, {x0, y0 + half}, {x0 + half, y0 + half}};
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            CsAssets.Sheet t = assets.tga(parts[i]);
            if (t == null) continue;
            any = true;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, t.id(), at[i][0], at[i][1], 0, 0, half, half, t.width(), t.height(), t.width(), t.height(), 0xFFFFFFFF);
        }
        if (!any) return;
        // black outside the lens; overlap the arcs by a few pixels so no light seam shows at their edges
        ctx.fill(0, 0, x0 + 2, gh, 0xFF000000);
        ctx.fill(x0 + size - 2, 0, gw, gh, 0xFF000000);
        ctx.fill(x0, gh / 2, x0 + size, gh / 2 + 1, 0xFF000000);
        ctx.fill(gw / 2, 0, gw / 2 + 1, gh, 0xFF000000);
    }

    static float alpha(float fade) { return MIN_ALPHA + (1f - MIN_ALPHA) * fade; }

    static int digits(int n) { return Math.max(1, Integer.toString(Math.abs(n)).length()); }

    /** sheets/status_icons.json */
    static java.util.List<String> statusIcons(PlayerEntity p) {
        java.util.List<String> out = new java.util.ArrayList<>(4);
        if (p.getAir() < p.getMaxAir()) out.add("dmg_drown");
        if (p.isOnFire()) out.add("dmg_heat");
        if (p.hasStatusEffect(StatusEffects.POISON) || p.hasStatusEffect(StatusEffects.WITHER)) out.add("dmg_poison");
        if (p.getFrozenTicks() > 0) out.add("dmg_cold");
        return out;
    }

    // ---- drawing helpers (positions in CS pixels) ----

    void sprite(DrawContext ctx, String name, float x, float y, int[] rgb, float a) {
        CsAssets.Region r = assets.region(name);
        if (r != null) spritePart(ctx, name, x, y, 0, r.w(), rgb, a);
    }

    /** Draws columns [u0, u0+w) of a hud.txt region. */
    void spritePart(DrawContext ctx, String name, float x, float y, int u0, int w, int[] rgb, float a) {
        regionPart(ctx, assets.region(name), x, y, u0, w, rgb, a);
    }

    /** A region from a weapon's sprites/<weapon>.txt. */
    void region(DrawContext ctx, CsAssets.Region r, float x, float y, int[] rgb, float a) {
        if (r != null) regionPart(ctx, r, x, y, 0, r.w(), rgb, a);
    }

    void regionPart(DrawContext ctx, CsAssets.Region r, float x, float y, int u0, int w, int[] rgb, float a) {
        if (r == null || w <= 0 || a <= 0.01f) return;
        CsAssets.Sheet sh = assets.spriteSheet(r.sheet());
        if (sh == null) return;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x * s, y * s);
        ctx.getMatrices().scale(s, s);
        RenderPipeline pipeline;
        int color;
        if (sh.additive()) { // SPR_DrawAdditive: tint scaled by alpha, added to the screen
            pipeline = additivePipeline();
            color = 0xFF000000 | (Math.round(rgb[0] * a) << 16) | (Math.round(rgb[1] * a) << 8) | Math.round(rgb[2] * a);
        } else {
            pipeline = RenderPipelines.GUI_TEXTURED;
            color = (Math.round(a * 255) << 24) | 0xFFFFFF;
        }
        ctx.drawTexture(pipeline, sh.id(), 0, 0, r.x() + u0, r.y(), w, r.h(), w, r.h(), sh.width(), sh.height(), color);
        ctx.getMatrices().popMatrix();
    }

    private static RenderPipeline additive;

    /** GUI_TEXTURED with additive blending, the way GoldSrc draws HUD sprites. */
    static RenderPipeline additivePipeline() {
        if (additive == null) {
            RenderPipeline src = RenderPipelines.GUI_TEXTURED;
            RenderPipeline.Builder b = RenderPipeline.builder()
                    .withLocation(Identifier.of(CsHudCraft.MOD_ID, "pipeline/hud_additive"))
                    .withVertexShader(src.getVertexShader())
                    .withFragmentShader(src.getFragmentShader())
                    .withVertexFormat(src.getVertexFormat(), src.getVertexFormatMode())
                    .withDepthTestFunction(src.getDepthTestFunction())
                    .withCull(src.isCull())
                    .withColorWrite(src.isWriteColor(), src.isWriteAlpha())
                    .withDepthWrite(src.isWriteDepth())
                    .withBlend(BlendFunction.ADDITIVE);
            for (String sampler : src.getSamplers()) b.withSampler(sampler);
            for (RenderPipeline.UniformDescription u : src.getUniforms()) {
                if (u.textureFormat() != null) b.withUniform(u.name(), u.type(), u.textureFormat());
                else b.withUniform(u.name(), u.type());
            }
            for (String flag : src.getShaderDefines().flags()) b.withShaderDefine(flag);
            src.getShaderDefines().values().forEach((k, v) -> {
                try { b.withShaderDefine(k, Integer.parseInt(v)); } catch (NumberFormatException e) { b.withShaderDefine(k, Float.parseFloat(v)); }
            });
            additive = b.build();
        }
        return additive;
    }

    /** CS's DrawHudNumber: fixed-width digits; minDigits pads with blanks like DHN_3DIGITS. Returns the x after it. */
    float number(DrawContext ctx, float x, float y, int value, int minDigits, int[] rgb, float a) {
        int fw = assets.region("number_0").w();
        String str = Integer.toString(Math.max(0, value));
        x += Math.max(0, minDigits - str.length()) * fw;
        for (char c : str.toCharArray()) {
            sprite(ctx, "number_" + c, x, y, rgb, a);
            x += fw;
        }
        return x;
    }

    void colon(DrawContext ctx, float x, float y, int fh, int[] rgb, float a) {
        int col = 0xFF000000 | ((int) (rgb[0] * a) << 16) | ((int) (rgb[1] * a) << 8) | (int) (rgb[2] * a);
        int d = Math.max(1, Math.round(3 * s));
        int gx = Math.round(x * s);
        ctx.fill(gx, Math.round((y + fh * 0.3f) * s), gx + d, Math.round((y + fh * 0.3f) * s) + d, col);
        ctx.fill(gx, Math.round((y + fh * 0.7f) * s), gx + d, Math.round((y + fh * 0.7f) * s) + d, col);
    }

    void radar(DrawContext ctx, ClientPlayerEntity p) {
        CsAssets.Region r = assets.region("radar");
        if (r == null) return;
        sprite(ctx, "radar", 0, 0, new int[]{255, 255, 255}, 0.75f);
        float cx = r.w() / 2f, cy = r.h() / 2f, radius = r.w() / 2f - 8;
        float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        float fx = -MathHelper.sin(yaw), fz = MathHelper.cos(yaw);   // forward
        float rx = -MathHelper.cos(yaw), rz = -MathHelper.sin(yaw);  // right
        Box box = p.getBoundingBox().expand(RADAR_RANGE);
        int dot = Math.max(2, Math.round(4 * s));
        for (LivingEntity e : p.getEntityWorld().getEntitiesByClass(LivingEntity.class, box, e -> e != p && e.isAlive())) {
            double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > RADAR_RANGE) continue;
            float sx = (float) (dx * rx + dz * rz) / RADAR_RANGE * radius;
            float sy = (float) -(dx * fx + dz * fz) / RADAR_RANGE * radius;
            int col = e instanceof PlayerEntity ? 0xFFFFFFFF : e instanceof Monster ? 0xFFFF2020 : 0xFF20E020;
            int gx = Math.round((cx + sx) * s), gy = Math.round((cy + sy) * s);
            ctx.fill(gx - dot / 2, gy - dot / 2, gx - dot / 2 + dot, gy - dot / 2 + dot, col);
        }
        int me = Math.round(cx * s), mey = Math.round(cy * s);
        ctx.fill(me - dot / 2, mey - dot / 2, me - dot / 2 + dot, mey - dot / 2 + dot, 0xFFFFFF60);
    }

    /** CS 1.6 line crosshair, drawn in real framebuffer pixels; spreads while moving like CS's dynamic crosshair. */
    void crosshair(DrawContext ctx, MinecraftClient mc, ClientPlayerEntity p) {
        if (!mc.options.getPerspective().isFirstPerson() || mc.getDebugHud().shouldShowDebugHud()) return;
        double gs = mc.getWindow().getScaleFactor();
        int fbw = mc.getWindow().getFramebufferWidth(), fbh = mc.getWindow().getFramebufferHeight();
        float unit = fbh / 480f;
        float target = ClientCs.crosshairSpread(p); // weapon's gap, widened by moving, jumping and firing
        crosshairGap += (target - crosshairGap) * 0.25f;
        int gap = Math.round(crosshairGap * unit);
        int len = Math.max(3, Math.round(5.5f * unit));
        int th = Math.max(1, Math.round(unit * 0.75f));
        int cx = fbw / 2, cy = fbh / 2;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale((float) (1 / gs), (float) (1 / gs));
        ctx.fill(cx - gap - len, cy - th / 2, cx - gap, cy - th / 2 + th, CROSSHAIR_ARGB);
        ctx.fill(cx + gap + 1, cy - th / 2, cx + gap + 1 + len, cy - th / 2 + th, CROSSHAIR_ARGB);
        ctx.fill(cx - th / 2, cy - gap - len, cx - th / 2 + th, cy - gap, CROSSHAIR_ARGB);
        ctx.fill(cx - th / 2, cy + gap + 1, cx - th / 2 + th, cy + gap + 1 + len, CROSSHAIR_ARGB);
        ctx.getMatrices().popMatrix();
    }

    void killFeed(DrawContext ctx, TextRenderer tr, float topCs) {
        float lineY = topCs;
        for (KillFeed.Entry e : feed.current()) {
            CsAssets.Region icon = assets.region(e.icon());
            if (icon == null) continue;
            float gy = lineY * s;
            int right = Math.round(vw * s) - 6;
            int vx = right - tr.getWidth(e.victim());
            int textY = Math.round(gy + (icon.h() * s - 8) / 2f);
            ctx.drawTextWithShadow(tr, e.victim(), vx, textY, color(e.victimSide()));
            float iconX = vx / s - 4 - icon.w();
            if (e.headshot()) {
                CsAssets.Region hs = assets.region("d_headshot");
                if (hs != null) {
                    sprite(ctx, "d_headshot", iconX, lineY, FEED_ICON, 1f);
                    iconX -= hs.w() + 2;
                }
                sprite(ctx, e.icon(), iconX, lineY, FEED_ICON, 1f);
            } else {
                sprite(ctx, e.icon(), iconX, lineY, FEED_ICON, 1f);
            }
            if (!e.killer().isEmpty()) {
                int kx = Math.round(iconX * s) - 4 - tr.getWidth(e.killer());
                ctx.drawTextWithShadow(tr, e.killer(), kx, textY, color(e.killerSide()));
            }
            lineY += icon.h() + 6;
        }
    }

    static int color(KillFeed.Side side) {
        return switch (side) {
            case CT -> CT_COLOR;
            case T -> T_COLOR;
            default -> NEUTRAL_COLOR;
        };
    }
}
