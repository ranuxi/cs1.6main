package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.ClientCs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Per-frame CS view punch, and scope zoom for the sniper rifles, AUG and SG552 (weapons.json zoomFov). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void cshudcraft$frame(CallbackInfo ci) {
        ClientCs.frame(MinecraftClient.getInstance());
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void cshudcraft$zoom(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        float z = ClientCs.zoomFov();
        if (z > 0 && changingFov) cir.setReturnValue(Math.min(cir.getReturnValue(), z));
    }
}
