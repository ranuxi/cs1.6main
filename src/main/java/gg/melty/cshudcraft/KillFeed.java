package gg.melty.cshudcraft;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * CS death notices (sheets/killfeed_icons.json; CS weapons use their own d_<weapon> icon). The server
 * sends every kill or death involving a player to everyone on it as a "kill" event, so all players in a
 * shared world see the same feed.
 */
public final class KillFeed {
    public enum Side { CT, T, NEUTRAL }

    public record Entry(String killer, Side killerSide, String icon, boolean headshot, String victim, Side victimSide, long shownAt) {}

    public static final int MAX = 5;
    public static final long SHOW_MS = 6000;

    /** Filled from network events, drained on the client thread. */
    private static final ConcurrentLinkedQueue<Entry> INCOMING = new ConcurrentLinkedQueue<>();
    private final Deque<Entry> shown = new ArrayDeque<>();
    public static volatile boolean enemyDownPending;

    /** A kill made with a CS weapon. */
    static void onCsKill(PlayerEntity killer, LivingEntity victim, String weapon, boolean headshot) {
        String icon = weapon.equals("hegrenade") || weapon.equals("c4") ? "d_grenade" : "d_" + weapon;
        broadcast(victim, new Entry(killer.getName().getString(), Side.CT, icon, headshot, victim.getName().getString(), side(victim), 0),
                victim instanceof Monster || victim instanceof PlayerEntity ? killer : null);
    }

    /** Any other death involving a player. */
    static void onDeath(LivingEntity victim, DamageSource source) {
        Entity attacker = source.getAttacker();
        if (victim instanceof PlayerEntity p) {
            String killer = attacker != null && attacker != victim ? attacker.getName().getString() : "";
            broadcast(victim, new Entry(killer, side(attacker), "d_skull", false, p.getName().getString(), Side.CT, 0), null);
            return;
        }
        if (!(attacker instanceof PlayerEntity player)) return;
        ItemStack weapon = source.getWeaponStack();
        if (weapon == null || weapon.isEmpty()) weapon = player.getMainHandStack();
        broadcast(victim, new Entry(player.getName().getString(), Side.CT, iconFor(weapon), false,
                victim.getName().getString(), side(victim), 0), null);
    }

    /** Sends the notice to every player on the server; the one who scored an enemy kill also hears "enemy down". */
    private static void broadcast(LivingEntity victim, Entry e, PlayerEntity enemyDownFor) {
        MinecraftServer server = victim.getEntityWorld().getServer();
        if (server == null) return;
        String arg = String.join("\n", e.killer(), e.killerSide().name(), e.icon(), e.victim(), e.victimSide().name());
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            CsServer.send(p, "kill", arg, e.headshot() ? 1f : 0f, p == enemyDownFor ? 1f : 0f);
        }
    }

    /** Client side: a "kill" event from the server. */
    static void onEvent(String arg, List<Float> nums) {
        String[] f = arg.split("\n", -1);
        if (f.length < 5) return;
        INCOMING.add(new Entry(f[0], side(f[1]), f[2], !nums.isEmpty() && nums.get(0) > 0, f[3], side(f[4]), 0));
        MpTest.onKill(f[0], f[2], f[3]);
        if (nums.size() > 1 && nums.get(1) > 0) enemyDownPending = true;
    }

    private static Side side(String name) {
        try {
            return Side.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return Side.NEUTRAL;
        }
    }

    static Side side(Entity e) {
        if (e instanceof PlayerEntity) return Side.CT;
        if (e instanceof Monster) return Side.T;
        return Side.NEUTRAL;
    }

    /** killfeed_icons.json, for Minecraft weapons. */
    static String iconFor(ItemStack s) {
        if (s.isIn(ItemTags.SWORDS) || s.isIn(ItemTags.AXES)) return "d_knife";
        if (s.isOf(Items.BOW)) return "d_scout";
        if (s.isOf(Items.CROSSBOW)) return "d_awp";
        if (s.isOf(Items.TRIDENT)) return "d_sg552";
        if (s.isOf(Items.MACE)) return "d_m249";
        if (s.isOf(Items.TNT) || s.isOf(Items.FLINT_AND_STEEL)) return "d_grenade";
        return "d_knife";
    }

    public Iterable<Entry> current() {
        long now = System.currentTimeMillis();
        Entry e;
        while ((e = INCOMING.poll()) != null) {
            shown.addLast(new Entry(e.killer(), e.killerSide(), e.icon(), e.headshot(), e.victim(), e.victimSide(), now));
            while (shown.size() > MAX) shown.removeFirst();
        }
        while (!shown.isEmpty() && now - shown.peekFirst().shownAt() > SHOW_MS) shown.removeFirst();
        return shown;
    }
}
