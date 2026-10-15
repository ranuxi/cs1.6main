package gg.melty.cshudcraft;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedQueue;

/** CS death notices for the player's kills and deaths (sheets/killfeed_icons.json; CS weapons use their own d_<weapon> icon). */
public final class KillFeed {
    public enum Side { CT, T, NEUTRAL }

    public record Entry(String killer, Side killerSide, String icon, boolean headshot, String victim, Side victimSide, long shownAt) {}

    public static final int MAX = 5;
    public static final long SHOW_MS = 6000;

    /** Filled from the integrated server thread, drained on the client thread. */
    private static final ConcurrentLinkedQueue<Entry> INCOMING = new ConcurrentLinkedQueue<>();
    private final Deque<Entry> shown = new ArrayDeque<>();
    public static volatile boolean enemyDownPending;

    /** A kill made with a CS weapon. */
    static void onCsKill(PlayerEntity killer, LivingEntity victim, String weapon, boolean headshot) {
        String icon = weapon.equals("hegrenade") ? "d_grenade" : "d_" + weapon;
        INCOMING.add(new Entry(killer.getName().getString(), Side.CT, icon, headshot, victim.getName().getString(), side(victim), 0));
        if (victim instanceof Monster || victim instanceof PlayerEntity) enemyDownPending = true;
    }

    /** Any other death involving a player (single player). */
    static void onDeath(LivingEntity victim, DamageSource source) {
        Entity attacker = source.getAttacker();
        if (victim instanceof PlayerEntity p) {
            String killer = attacker != null && attacker != victim ? attacker.getName().getString() : "";
            INCOMING.add(new Entry(killer, side(attacker), "d_skull", false, p.getName().getString(), Side.CT, 0));
            return;
        }
        if (!(attacker instanceof PlayerEntity player)) return;
        ItemStack weapon = source.getWeaponStack();
        if (weapon == null || weapon.isEmpty()) weapon = player.getMainHandStack();
        INCOMING.add(new Entry(player.getName().getString(), Side.CT, iconFor(weapon), false,
                victim.getName().getString(), side(victim), 0));
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
