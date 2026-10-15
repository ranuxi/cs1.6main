package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.CsServer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CS kevlar: absorbs part of bullet, melee and explosion damage (economy.json armorRatio). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityArmorMixin {
    @Inject(method = "modifyAppliedDamage", at = @At("RETURN"), cancellable = true)
    private void cshudcraft$kevlar(DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof ServerPlayerEntity p) cir.setReturnValue(CsServer.absorb(p, source, cir.getReturnValue()));
    }
}
