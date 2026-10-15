package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.ClientCs;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In CS mode the attack, use and hotbar keys belong to the weapons (controls.json), so nothing acts twice. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void cshudcraft$attack(CallbackInfoReturnable<Boolean> cir) {
        if (ClientCs.takesInput()) cir.setReturnValue(false);
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void cshudcraft$use(CallbackInfo ci) {
        if (ClientCs.takesInput()) ci.cancel();
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void cshudcraft$breaking(boolean breaking, CallbackInfo ci) {
        if (ClientCs.takesInput()) ci.cancel();
    }

    @Inject(method = "handleInputEvents", at = @At("HEAD"))
    private void cshudcraft$hotbar(CallbackInfo ci) {
        if (ClientCs.takesInput()) ClientCs.consumeHotbarKeys((MinecraftClient) (Object) this);
    }
}
