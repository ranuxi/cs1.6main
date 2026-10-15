package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.ViewModelRenderer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First-person hands: CS 1.6 hands and weapon instead of Minecraft's arm while CS mode is on. */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Inject(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at = @At("HEAD"), cancellable = true)
    private void cshudcraft$viewmodel(float tickProgress, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                      ClientPlayerEntity player, int light, CallbackInfo ci) {
        if (ViewModelRenderer.render(tickProgress, matrices, queue, player, light)) ci.cancel();
    }
}
