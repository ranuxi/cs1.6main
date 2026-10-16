package gg.melty.cshudcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The design sheets (sheets/*.json in the project, packed into the jar) are the source of truth:
 * each row becomes one record here, column for column. Nothing about weapons, ammo, prices or awards
 * is hard-coded anywhere else.
 */
public final class Sheets {
    public record Weapon(String id, String name, int slot, String category, int price, int unlockLevel, String model,
                         String ammo, int clip, float damage, int pellets, float cycle, boolean auto, float rangeBlocks,
                         float rangeModifier, float spreadBase, float spreadMove, float spreadAir, float spreadGrow,
                         float spreadMaxMul, float zoomFov, float zoomSpread, float recoilUp, float recoilUpGrow,
                         float recoilSide, float recoilUpMax, float speed, int killReward, String fireSound,
                         String seqIdle, String seqDraw, List<String> seqShoot, String seqShootEmpty, String seqReload,
                         String reloadMode, String hud, float crosshairGap) {
        public boolean isKnife() { return id.equals("knife"); }
        public boolean shellReload() { return reloadMode.equals("shell"); }
        public boolean canZoom() { return zoomFov > 0; }
    }

    public record Ammo(String id, String name, int packSize, int packPrice, int maxReserve, String icon) {}

    public record Equipment(String id, String name, String category, int price, int unlockLevel, int max, String model,
                            String worldModel, String seqIdle, String seqDraw, String seqPull, String seqThrow,
                            String effect, String hud) {
        public boolean isGrenade() { return category.equals("grenades"); }
    }

    public record Award(String id, int money, int xp) {}

    /** inspect.json: a weapon whose inspect turns its hand bones instead of tilting the whole model. */
    public record Inspect(String id, String mode, String[] bones, float ax, float ay, float az, float lift) {}

    public static final Map<String, Weapon> WEAPONS = new LinkedHashMap<>();
    public static final Map<String, Ammo> AMMO = new LinkedHashMap<>();
    public static final Map<String, Equipment> EQUIPMENT = new LinkedHashMap<>();
    public static final Map<String, Award> AWARDS = new LinkedHashMap<>();
    public static final Map<String, Double> ECONOMY = new LinkedHashMap<>();
    public static final Map<String, Double> BLOCK_SETTINGS = new LinkedHashMap<>();
    public static final Map<String, Inspect> INSPECT = new LinkedHashMap<>();
    /** block_durability.json overrides: block id -> CS hp. */
    public static final Map<String, Integer> BLOCK_HP = new LinkedHashMap<>();

    /** Buy-menu categories in CS order (weapons.json/equipment.json "category"; "ammo" is the ammo sheet). */
    public static final List<String> CATEGORIES = List.of(
            "pistols", "shotguns", "smgs", "rifles", "snipers", "machineguns", "knives", "equipment", "grenades", "ammo");

    private Sheets() {}

    public static void load() {
        if (!WEAPONS.isEmpty()) return;
        for (JsonElement e : rows(read("weapons"))) {
            JsonObject r = e.getAsJsonObject();
            List<String> shoot = new ArrayList<>();
            for (JsonElement s : r.getAsJsonArray("seqShoot")) shoot.add(s.getAsString());
            WEAPONS.put(s(r, "id"), new Weapon(s(r, "id"), s(r, "name"), i(r, "slot"), s(r, "category"), i(r, "price"),
                    i(r, "unlockLevel"), s(r, "model"), s(r, "ammo"), i(r, "clip"), f(r, "damage"), i(r, "pellets"),
                    f(r, "cycle"), r.get("auto").getAsBoolean(), f(r, "rangeBlocks"), f(r, "rangeModifier"),
                    f(r, "spreadBase"), f(r, "spreadMove"), f(r, "spreadAir"), f(r, "spreadGrow"), f(r, "spreadMaxMul"),
                    f(r, "zoomFov"), f(r, "zoomSpread"), f(r, "recoilUp"), f(r, "recoilUpGrow"), f(r, "recoilSide"),
                    f(r, "recoilUpMax"), f(r, "speed"), i(r, "killReward"), s(r, "fireSound"), s(r, "seqIdle"),
                    s(r, "seqDraw"), Collections.unmodifiableList(shoot), s(r, "seqShootEmpty"), s(r, "seqReload"),
                    s(r, "reloadMode"), s(r, "hud"), f(r, "crosshairGap")));
        }
        for (JsonElement e : rows(read("ammo"))) {
            JsonObject r = e.getAsJsonObject();
            AMMO.put(s(r, "id"), new Ammo(s(r, "id"), s(r, "name"), i(r, "packSize"), i(r, "packPrice"), i(r, "maxReserve"), s(r, "icon")));
        }
        for (JsonElement e : rows(read("equipment"))) {
            JsonObject r = e.getAsJsonObject();
            EQUIPMENT.put(s(r, "id"), new Equipment(s(r, "id"), s(r, "name"), s(r, "category"), i(r, "price"),
                    i(r, "unlockLevel"), i(r, "max"), s(r, "model"), s(r, "worldModel"), s(r, "seqIdle"), s(r, "seqDraw"),
                    s(r, "seqPull"), s(r, "seqThrow"), s(r, "effect"), s(r, "hud")));
        }
        JsonObject eco = read("economy");
        for (JsonElement e : rows(eco.getAsJsonObject("settings"))) {
            JsonObject r = e.getAsJsonObject();
            ECONOMY.put(s(r, "key"), r.get("value").getAsDouble());
        }
        for (JsonElement e : rows(eco.getAsJsonObject("awards"))) {
            JsonObject r = e.getAsJsonObject();
            AWARDS.put(s(r, "id"), new Award(s(r, "id"), i(r, "money"), i(r, "xp")));
        }
        for (JsonElement e : rows(read("inspect"))) {
            JsonObject r = e.getAsJsonObject();
            INSPECT.put(s(r, "id"), new Inspect(s(r, "id"), s(r, "mode"), s(r, "bones").split(";"),
                    f(r, "ax"), f(r, "ay"), f(r, "az"), f(r, "lift")));
        }
        JsonObject blocks = read("block_durability");
        for (JsonElement e : rows(blocks.getAsJsonObject("settings"))) {
            JsonObject r = e.getAsJsonObject();
            BLOCK_SETTINGS.put(s(r, "key"), r.get("value").getAsDouble());
        }
        for (JsonElement e : rows(blocks.getAsJsonObject("overrides"))) {
            JsonObject r = e.getAsJsonObject();
            BLOCK_HP.put(s(r, "id"), i(r, "hp"));
        }
        CsHudCraft.LOG.info("[CSHudCraft] Sheets: {} weapons, {} ammo types, {} equipment, {} block overrides", WEAPONS.size(), AMMO.size(), EQUIPMENT.size(), BLOCK_HP.size());
    }

    public static double eco(String key) {
        Double v = ECONOMY.get(key);
        if (v == null) throw new IllegalStateException("economy.json has no " + key);
        return v;
    }

    public static double block(String key) {
        Double v = BLOCK_SETTINGS.get(key);
        if (v == null) throw new IllegalStateException("block_durability.json has no " + key);
        return v;
    }

    public static Weapon weapon(String id) { return id == null ? null : WEAPONS.get(id); }

    public static int xpForNextLevel(int level) { return (int) (level * eco("xpPerLevelBase")); }

    private static JsonObject read(String name) {
        try (InputStream in = Sheets.class.getResourceAsStream("/cshudcraft/sheets/" + name + ".json")) {
            if (in == null) throw new IllegalStateException("missing sheet " + name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read sheet " + name, e);
        }
    }

    private static JsonArray rows(JsonObject table) { return table.getAsJsonArray("rows"); }
    private static String s(JsonObject r, String k) { return r.get(k).getAsString(); }
    private static int i(JsonObject r, String k) { return r.get(k).getAsInt(); }
    private static float f(JsonObject r, String k) { return r.get(k).getAsFloat(); }
}
