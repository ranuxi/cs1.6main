package gg.melty.cshudcraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The CS 1.6 flashlight (F): a spot of light where the player looks, made of one invisible Minecraft
 * light block that follows the beam, so everyone in the world sees it light up. A battery drains while
 * it is on and recharges while it is off (systems.json "flashlight"); an empty battery turns it off.
 */
final class Flashlight {
    private record Spot(RegistryKey<World> world, BlockPos pos) {}

    private static final Map<UUID, Spot> SPOTS = new HashMap<>();
    private static final Map<UUID, Long> BATTERY_AT = new HashMap<>();

    private Flashlight() {}

    /** F pressed: on or off, with CS's click for everyone close by. */
    static void toggle(ServerPlayerEntity p, CsState s) {
        if (!s.csMode || !p.isAlive()) return;
        if (!s.flashlight && s.battery <= 0) return;
        s.flashlight = !s.flashlight;
        CsServer.save(p, s);
        CsServer.soundAround(p.getEntityWorld(), p.getEyePos(), "items/flashlight1.wav", 0.8f, null, 16);
        if (!s.flashlight) remove(p.getUuid(), p.getEntityWorld().getServer());
    }

    /** Turned off without the click (death, Minecraft mode, leaving). */
    static void off(ServerPlayerEntity p, CsState s) {
        remove(p.getUuid(), p.getEntityWorld().getServer());
        if (s.flashlight) {
            s.flashlight = false;
            CsServer.save(p, s);
        }
    }

    /** Every server tick for every player. */
    static void tick(ServerPlayerEntity p, CsState s) {
        long now = System.currentTimeMillis();
        long last = BATTERY_AT.getOrDefault(p.getUuid(), now);
        long step = (long) ((s.flashlight ? Sheets.flash("drainSeconds") : Sheets.flash("chargeSeconds")) * 1000);
        if (now - last >= step) {
            BATTERY_AT.put(p.getUuid(), now);
            int b = MathHelper.clamp(s.battery + (s.flashlight ? -1 : 1), 0, 100);
            if (b != s.battery) {
                s.battery = b;
                if (b == 0) {
                    off(p, s);
                    return;
                }
                CsServer.save(p, s);
            }
        } else if (!BATTERY_AT.containsKey(p.getUuid())) {
            BATTERY_AT.put(p.getUuid(), now);
        }
        if (!s.flashlight) return;
        if (!s.csMode || !p.isAlive() || p.isSpectator()) {
            off(p, s);
            return;
        }
        if (p.age % 2 == 0) aim(p);
    }

    /** Moves the light block to where the beam ends, brightest up close and dimmer further off; nothing past rangeBlocks. */
    private static void aim(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        Vec3d eye = p.getEyePos(), dir = p.getRotationVec(1f);
        double range = Sheets.flash("rangeBlocks");
        BlockHitResult hit = w.raycast(new RaycastContext(eye, eye.add(dir.multiply(range)), RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) {
            remove(p.getUuid(), w.getServer());
            return;
        }
        double d = eye.distanceTo(hit.getPos());
        double near = Sheets.flash("nearLevel");
        int level = (int) Math.round(near + (Sheets.flash("farLevel") - near) * MathHelper.clamp(d / range, 0, 1));
        BlockPos[] tries = {
                hit.getBlockPos().offset(hit.getSide()),
                BlockPos.ofFloored(hit.getPos().subtract(dir.multiply(0.6))),
                BlockPos.ofFloored(hit.getPos().subtract(dir.multiply(1.5)))
        };
        Spot old = SPOTS.get(p.getUuid());
        for (BlockPos pos : tries) {
            BlockState st = w.getBlockState(pos);
            boolean ours = old != null && old.world() == w.getRegistryKey() && old.pos().equals(pos);
            boolean water = st.isOf(Blocks.WATER) && st.getFluidState().isStill();
            if (!(st.isAir() || water || (ours && st.isOf(Blocks.LIGHT)))) continue;
            if (!ours) remove(p.getUuid(), w.getServer());
            boolean wet = water || (st.isOf(Blocks.LIGHT) && st.get(Properties.WATERLOGGED));
            BlockState light = Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, level).with(Properties.WATERLOGGED, wet);
            if (!st.equals(light)) w.setBlockState(pos, light, Block.NOTIFY_LISTENERS);
            SPOTS.put(p.getUuid(), new Spot(w.getRegistryKey(), pos.toImmutable()));
            return;
        }
        remove(p.getUuid(), w.getServer());
    }

    /** Takes a player's light block away again (only if it is still a light block). */
    static void remove(UUID player, MinecraftServer server) {
        Spot old = SPOTS.remove(player);
        if (old == null || server == null) return;
        ServerWorld w = server.getWorld(old.world());
        if (w == null) return;
        BlockState st = w.getBlockState(old.pos());
        if (!st.isOf(Blocks.LIGHT)) return;
        w.setBlockState(old.pos(), st.get(Properties.WATERLOGGED) ? Fluids.WATER.getDefaultState().getBlockState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    /** Server stopping: no light block may stay behind in the saved world. */
    static void removeAll(MinecraftServer server) {
        for (UUID id : SPOTS.keySet().toArray(new UUID[0])) remove(id, server);
        BATTERY_AT.clear();
    }

    static void forget(ServerPlayerEntity p) {
        remove(p.getUuid(), p.getEntityWorld().getServer());
        BATTERY_AT.remove(p.getUuid());
    }
}
