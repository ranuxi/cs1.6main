package gg.melty.cshudcraft;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.ThrownEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/** A thrown CS grenade (equipment.json grenades): bounces, then goes off after 1.5 s. Never breaks blocks. */
public class GrenadeEntity extends ThrownEntity {
    private static final TrackedData<String> KIND = DataTracker.registerData(GrenadeEntity.class, TrackedDataHandlerRegistry.STRING);
    static final int FUSE_TICKS = 30;

    public GrenadeEntity(EntityType<? extends GrenadeEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(KIND, "hegrenade");
    }

    public void setKind(String kind) { dataTracker.set(KIND, kind); }
    public String getKind() { return dataTracker.get(KIND); }

    @Override
    protected double getGravity() { return 0.04; }

    @Override
    public void tick() {
        super.tick();
        if (!getEntityWorld().isClient() && age >= FUSE_TICKS) {
            detonate((ServerWorld) getEntityWorld());
            discard();
        }
    }

    @Override
    protected void onBlockHit(BlockHitResult hit) {
        Vec3d v = getVelocity();
        Direction face = hit.getSide();
        Vec3d n = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
        Vec3d reflected = v.subtract(n.multiply(2 * v.dotProduct(n))).multiply(0.45);
        setVelocity(reflected);
        Vec3d at = hit.getPos().add(n.multiply(0.13));
        setPosition(at.x, at.y, at.z);
        if (!getEntityWorld().isClient() && v.length() > 0.12) {
            CsServer.soundAround((ServerWorld) getEntityWorld(), getEntityPos(), "weapons/he_bounce-1.wav", 0.7f, null, 24);
        }
    }

    @Override
    protected void onEntityHit(EntityHitResult hit) {
        setVelocity(getVelocity().multiply(-0.2, 0.5, -0.2));
    }

    @Override
    protected void onCollision(HitResult hit) {
        if (hit.getType() == HitResult.Type.BLOCK) onBlockHit((BlockHitResult) hit);
        else if (hit.getType() == HitResult.Type.ENTITY) onEntityHit((EntityHitResult) hit);
        // never discard on impact: CS grenades go off on their fuse
    }

    private void detonate(ServerWorld w) {
        Vec3d c = getEntityPos();
        PlayerEntity owner = getOwner() instanceof PlayerEntity p ? p : null;
        switch (getKind()) {
            case "flashbang" -> {
                w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 30, 0.2, 0.2, 0.2, 0.25);
                CsServer.soundAround(w, c, "weapons/flashbang-" + (random.nextBoolean() ? 1 : 2) + ".wav", 1f, null, 64);
                for (ServerPlayerEntity p : w.getPlayers()) {
                    double d = p.getEyePos().distanceTo(c);
                    if (d > 20 || !visible(w, p.getEyePos(), c)) continue;
                    Vec3d to = c.subtract(p.getEyePos()).normalize();
                    double facing = p.getRotationVec(1f).dotProduct(to);
                    float strength = (float) ((facing > 0.6 ? 1.0 : facing > 0 ? 0.6 : 0.25) * (1 - d / 25));
                    if (strength > 0.05f) CsServer.send(p, "flash", "", strength);
                }
                for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(8), e -> !(e instanceof PlayerEntity))) {
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 3));
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 60, 1));
                }
            }
            case "smokegrenade" -> {
                CsServer.soundAround(w, c, "weapons/sg_explode.wav", 1f, null, 48);
                CsServer.addSmoke(new Smoke(w, c, 15 * 20));
            }
            default -> { // hegrenade
                w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                CsServer.soundAround(w, c, "weapons/hegrenade-" + (random.nextBoolean() ? 1 : 2) + ".wav", 1f, null, 96);
                if (Sheets.block("heDamage") > 0.5) { // blocks near the blast take damage too
                    BlockPos center = BlockPos.ofFloored(c);
                    ServerPlayerEntity by = owner instanceof ServerPlayerEntity sp ? sp : null;
                    for (BlockPos bp : BlockPos.iterate(center.add(-3, -3, -3), center.add(3, 3, 3))) {
                        double d = Vec3d.ofCenter(bp).distanceTo(c);
                        if (d <= 3.5) BlockDamage.damage(by, w, bp.toImmutable(), (float) (120 * (1 - d / 4.0)));
                    }
                }
                double radius = 7;
                for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(radius), LivingEntity::isAlive)) {
                    double d = e.getBoundingBox().getCenter().distanceTo(c);
                    if (d > radius || !visible(w, c, e.getBoundingBox().getCenter())) continue;
                    float cs = (float) (100 * (1 - d / radius));
                    CsServer.recordGrenadeHit(owner, e, "hegrenade");
                    e.timeUntilRegen = 0;
                    Entity src = owner != null ? owner : this;
                    e.damage(w, w.getDamageSources().explosion(this, src), cs * (float) Sheets.eco("damageScale"));
                }
            }
        }
    }

    private boolean visible(ServerWorld w, Vec3d a, Vec3d b) {
        return w.raycast(new RaycastContext(a, b, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this)).getType() == HitResult.Type.MISS;
    }

    /** A smoke grenade's cloud: particles for its lifetime. */
    public static final class Smoke {
        final ServerWorld world;
        final Vec3d at;
        int ticks;

        Smoke(ServerWorld world, Vec3d at, int ticks) {
            this.world = world;
            this.at = at;
            this.ticks = ticks;
        }

        /** @return true when finished */
        boolean tick() {
            if (--ticks <= 0) return true;
            if (ticks % 2 == 0) {
                float grow = MathHelper.clamp((15 * 20 - ticks) / 40f, 0.3f, 1f);
                world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y + 1.0 * grow, at.z, 8,
                        2.0 * grow, 1.2 * grow, 2.0 * grow, 0.003);
                world.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.8, at.z, 6, 1.6 * grow, 1.0 * grow, 1.6 * grow, 0.0);
            }
            return false;
        }
    }
}
