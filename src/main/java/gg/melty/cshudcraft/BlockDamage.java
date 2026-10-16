package gg.melty.cshudcraft;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Bullets, knives and HE grenades wear blocks down (sheets/block_durability.json): each block has hp in
 * CS damage, hits add up, the vanilla crack overlay shows how close it is, and at zero it breaks and drops
 * like mining. Damage heals after a while without hits. Server side only.
 */
public final class BlockDamage {
    private record Key(ServerWorld world, BlockPos pos) {}

    private static final class Hit {
        float damage;
        long last;
        final int crackId;

        Hit(int crackId) { this.crackId = crackId; }
    }

    private static final Map<Key, Hit> HITS = new HashMap<>();

    private BlockDamage() {}

    /** CS damage the block can take, or -1 if it can't be broken this way. */
    public static float hpOf(ServerWorld w, BlockPos pos, BlockState st) {
        if (st.isAir() || !st.getFluidState().isEmpty()) return -1;
        Integer override = Sheets.BLOCK_HP.get(Registries.BLOCK.getId(st.getBlock()).toString());
        if (override != null) return override;
        float hardness = st.getHardness(w, pos);
        if (hardness < 0) return -1;
        if (st.isIn(BlockTags.LEAVES)) return 4;
        return (float) (Sheets.block("base") + Sheets.block("scale") * Math.pow(hardness, Sheets.block("exponent")));
    }

    /** Adds CS damage to a block; breaks it when its hp is used up. */
    public static void damage(ServerPlayerEntity by, ServerWorld w, BlockPos pos, float cs) {
        if (Sheets.block("enabled") < 0.5 || cs <= 0) return;
        if (by != null && (!by.canModifyBlocks() || !w.canEntityModifyAt(by, pos))) return;
        BlockState st = w.getBlockState(pos);
        float hp = hpOf(w, pos, st);
        if (hp < 0) return;
        Key key = new Key(w, pos.toImmutable());
        Hit hit = HITS.computeIfAbsent(key, k -> new Hit(-(1 + (k.pos().hashCode() & 0x3FFFFFFF))));
        hit.damage += cs;
        hit.last = System.currentTimeMillis();
        if (hit.damage >= hp) {
            HITS.remove(key);
            w.setBlockBreakingInfo(hit.crackId, pos, -1);
            w.breakBlock(pos, true, by, 512);
        } else {
            w.setBlockBreakingInfo(hit.crackId, pos, Math.min(9, (int) (hit.damage / hp * 10)));
        }
    }

    /** Current damage share 0..1 of a block (for tests). */
    public static float progress(ServerWorld w, BlockPos pos) {
        Hit h = HITS.get(new Key(w, pos));
        float hp = hpOf(w, pos, w.getBlockState(pos));
        return h == null || hp <= 0 ? 0 : h.damage / hp;
    }

    /** Heals blocks that haven't been hit for healSeconds, and forgets blocks that changed. */
    static void tick() {
        long now = System.currentTimeMillis();
        long heal = (long) (Sheets.block("healSeconds") * 1000);
        for (Iterator<Map.Entry<Key, Hit>> it = HITS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Hit> e = it.next();
            Key k = e.getKey();
            if (now - e.getValue().last > heal || k.world().getBlockState(k.pos()).isAir()) {
                k.world().setBlockBreakingInfo(e.getValue().crackId, k.pos(), -1);
                it.remove();
            }
        }
    }
}
