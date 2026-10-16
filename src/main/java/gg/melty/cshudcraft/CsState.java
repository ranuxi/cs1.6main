package gg.melty.cshudcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything CS about one player: money and XP (kept apart), the loadout by CS slot, rounds in each
 * weapon's clip, reserve per ammo type, grenades, kevlar/helmet, and the chosen interface mode.
 * Saved with the player and synced to that player only. Switching interface never touches it.
 */
public final class CsState {
    public static final int SLOT_PRIMARY = 1, SLOT_PISTOL = 2, SLOT_KNIFE = 3, SLOT_GRENADE = 4, SLOT_BOMB = 5;
    private static final Gson GSON = new GsonBuilder().create();

    public int money;
    public int xp;
    public int level = 1;
    public String primary = "";
    public String pistol = "";
    public int slot = SLOT_KNIFE;
    public String grenade = "";
    public Map<String, Integer> clips = new LinkedHashMap<>();
    public Map<String, Integer> reserve = new LinkedHashMap<>();
    public Map<String, Integer> grenades = new LinkedHashMap<>();
    public int armor;
    public boolean helmet;
    /** Carrying a C4 (CS slot 5). */
    public boolean hasBomb;
    /** CS flashlight (F) on, and its battery 0-100 (systems.json "flashlight"). */
    public boolean flashlight;
    public int battery = 100;
    public boolean csMode = true;
    public boolean initialized;

    public static final Codec<CsState> CODEC = Codec.STRING.xmap(CsState::fromJson, CsState::toJson);
    public static final PacketCodec<RegistryByteBuf, CsState> PACKET_CODEC =
            PacketCodecs.STRING.<RegistryByteBuf>cast().xmap(CsState::fromJson, CsState::toJson);

    public String toJson() { return GSON.toJson(this); }

    public static CsState fromJson(String s) {
        CsState st = GSON.fromJson(s, CsState.class);
        if (st.clips == null) st.clips = new LinkedHashMap<>();
        if (st.reserve == null) st.reserve = new LinkedHashMap<>();
        if (st.grenades == null) st.grenades = new LinkedHashMap<>();
        if (st.primary == null) st.primary = "";
        if (st.pistol == null) st.pistol = "";
        if (st.grenade == null) st.grenade = "";
        return st;
    }

    public CsState copy() { return fromJson(toJson()); }

    /** economy.json start_loadout: knife, USP with a full clip and 24 spare rounds, $800. */
    public static CsState fresh() {
        CsState s = new CsState();
        s.money = (int) Sheets.eco("startMoney");
        s.giveStartLoadout();
        s.initialized = true;
        return s;
    }

    public void giveStartLoadout() {
        pistol = "usp";
        clips.put("usp", Sheets.weapon("usp").clip());
        reserve.merge("45acp", 24, Math::max);
        slot = SLOT_PISTOL;
    }

    /** Weapon in a CS slot, or null (slot 4 is grenades, handled separately). */
    public Sheets.Weapon weaponInSlot(int s) {
        return switch (s) {
            case SLOT_PRIMARY -> primary.isEmpty() ? null : Sheets.weapon(primary);
            case SLOT_PISTOL -> pistol.isEmpty() ? null : Sheets.weapon(pistol);
            case SLOT_KNIFE -> Sheets.weapon("knife");
            default -> null;
        };
    }

    public Sheets.Weapon current() { return weaponInSlot(slot); }

    public boolean hasSlot(int s) {
        if (s == SLOT_GRENADE) return grenades.values().stream().anyMatch(c -> c > 0);
        if (s == SLOT_BOMB) return hasBomb;
        return weaponInSlot(s) != null;
    }

    public int clip(String weaponId) { return clips.getOrDefault(weaponId, 0); }
    public int reserveOf(String ammoId) { return reserve.getOrDefault(ammoId, 0); }

    /** What others see in this player's hand (CsNet.HELD): the id of the weapon or item in the current slot, "" in Minecraft mode. */
    public String held() {
        if (!csMode) return "";
        return switch (slot) {
            case SLOT_GRENADE -> currentGrenade();
            case SLOT_BOMB -> hasBomb ? "c4" : "";
            default -> { Sheets.Weapon w = current(); yield w == null ? "" : w.id(); }
        };
    }

    /** Picks the first grenade still held when slot 4 is selected. */
    public String currentGrenade() {
        if (!grenade.isEmpty() && grenades.getOrDefault(grenade, 0) > 0) return grenade;
        for (Map.Entry<String, Integer> e : grenades.entrySet()) if (e.getValue() > 0) return e.getKey();
        return "";
    }
}
