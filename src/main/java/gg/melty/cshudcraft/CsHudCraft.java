package gg.melty.cshudcraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Client entry: the F6 switch between the CS 1.6 and Minecraft interfaces, CS keys, HUD layers and events. */
public class CsHudCraft implements ClientModInitializer {
    public static final String MOD_ID = "cshudcraft";
    public static final Logger LOG = LoggerFactory.getLogger("CSHudCraft");

    /** hud.txt names preloaded and checked at start (the rest load when first drawn). */
    public static final List<String> SPRITES_USED = List.of(
            "cross", "suit_full", "suit_empty", "suithelmet_full", "dollar", "divider", "stopwatch", "radar",
            "flash_empty", "flash_full", "flash_beam", "plus", "minus", "c4",
            "dmg_drown", "dmg_heat", "dmg_poison", "dmg_cold",
            "d_knife", "d_skull", "d_headshot",
            "number_0", "number_1", "number_2", "number_3", "number_4",
            "number_5", "number_6", "number_7", "number_8", "number_9");

    public static final CsAssets ASSETS = new CsAssets();
    public static final CsConfig CONFIG = new CsConfig();
    private static final KillFeed FEED = new KillFeed();
    private static final CsHud HUD = new CsHud(ASSETS, FEED);

    // sheets/controls.json
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
    public static KeyBinding TOGGLE, RELOAD, INSPECT, BUY, AMMO_PRIMARY, AMMO_SECONDARY, HAND;

    private static boolean enabled = true;
    private static int csFrames;
    private static volatile boolean vanillaDrawn;
    private static Vec3d lastShotDir;
    private static int shots;

    /** CS mode is on and the player's Counter-Strike files could be read. */
    public static boolean csActive() { return enabled && ASSETS.loaded(); }

    public static boolean enabled() { return enabled; }
    public static int csFrames() { return csFrames; }
    static void onCsHudFrame() { csFrames++; }
    public static boolean vanillaDrawn() { return vanillaDrawn; }
    static void onShot(Vec3d dir) { lastShotDir = dir; shots++; }
    static int shots() { return shots; }

    @Override
    public void onInitializeClient() {
        Sheets.load();
        CONFIG.load();
        enabled = CONFIG.enabled();
        TOGGLE = key("toggle", GLFW.GLFW_KEY_F6);
        RELOAD = key("reload", GLFW.GLFW_KEY_R);
        INSPECT = key("inspect", GLFW.GLFW_KEY_F);
        BUY = key("buy", GLFW.GLFW_KEY_B);
        AMMO_PRIMARY = key("ammo_primary", GLFW.GLFW_KEY_COMMA);
        AMMO_SECONDARY = key("ammo_secondary", GLFW.GLFW_KEY_PERIOD);
        HAND = key("hand", GLFW.GLFW_KEY_H);

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> loadAssets());
        // Minecraft's own HUD parts are skipped while the CS HUD is up (sheets/systems.json "vanilla_hidden")
        for (Identifier part : List.of(VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR,
                VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
                VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.CROSSHAIR, VanillaHudElements.HOTBAR,
                VanillaHudElements.HELD_ITEM_TOOLTIP)) {
            boolean marker = part.equals(VanillaHudElements.HEALTH_BAR);
            HudElementRegistry.replaceElement(part, original -> (ctx, tick) -> {
                if (csActive()) return;
                if (marker) vanillaDrawn = true;
                original.render(ctx, tick);
            });
        }
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of(MOD_ID, "cs_hud"), (ctx, tick) -> {
            if (csActive()) HUD.render(ctx);
        });
        ClientPlayNetworking.registerGlobalReceiver(CsNet.Event.ID, (payload, ctx) -> ClientCs.onEvent(payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientCs.onModeChanged(enabled);
            MeltyLink.onJoinWorld(client);
        });
        EntityRendererRegistry.register(CsServer.GRENADE, GrenadeRenderer::new);
        EntityRendererRegistry.register(CsServer.C4, C4Renderer::new);
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerEntityRenderer<?> pr) helper.register(new ThirdPersonWeapon(pr));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE.wasPressed()) setEnabled(!enabled, true);
            while (HAND.wasPressed()) {
                boolean left = !CONFIG.leftHanded();
                CONFIG.setLeftHanded(left);
                if (client.player != null) client.player.sendMessage(Text.translatable(left ? "cshudcraft.hand.left" : "cshudcraft.hand.right"), true);
            }
            ClientCs.tick(client);
            TestMode.tick(client);
            MeltyLink.tick(client);
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            TestMode.onScreen(client, screen);
            MeltyLink.onScreen(client, screen);
        });
        TestMode.init();
        MeltyLink.init();
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.cshudcraft." + name, InputUtil.Type.KEYSYM, code, CATEGORY));
    }

    static void loadAssets() {
        Path dir = null;
        String configured = CONFIG.csDir();
        if (!configured.isEmpty()) dir = CsAssets.normalize(Paths.get(configured));
        if (dir == null) dir = SteamFinder.find();
        ASSETS.load(dir);
        if (ASSETS.loaded()) {
            LOG.info("[CSHudCraft] Counter-Strike 1.6 files loaded from {}", ASSETS.halfLifeDir());
            ViewModelRenderer.model("v_knife.mdl");
        } else {
            LOG.error("[CSHudCraft] CS files unavailable: {}", ASSETS.error());
        }
        TestMode.onAssets();
    }

    /** F6: only the interface and controls change; money, XP, health, inventory, weapons and ammo stay as they are. */
    public static void setEnabled(boolean on, boolean save) {
        enabled = on;
        vanillaDrawn = false;
        if (save) CONFIG.setEnabled(on);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!ASSETS.loaded()) {
            if (mc.player != null) mc.player.sendMessage(Text.translatable("cshudcraft.missing", String.valueOf(ASSETS.error())).formatted(Formatting.RED), true);
            return;
        }
        ClientCs.onModeChanged(on);
        if (on) {
            HUD.fadeIn();
            CsSounds.play(ASSETS, CsSounds.HUD_ON, CsSounds.LOCK_LOAD);
        } else {
            CsSounds.play(ASSETS, CsSounds.HUD_OFF);
        }
        if (mc.player != null) {
            mc.player.sendMessage(Text.translatable(on ? "cshudcraft.mode.cs" : "cshudcraft.mode.mc").formatted(on ? Formatting.GOLD : Formatting.WHITE), true);
        }
    }
}
