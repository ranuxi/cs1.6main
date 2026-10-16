package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.ThirdPersonWeapon;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player holding a CS weapon: the CS model replaces the Minecraft item in hand, and the arms hold it like CS does. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    private static final boolean DEBUG = "1".equals(System.getenv("CSHUDCRAFT_POSE"));

    @Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V", at = @At("TAIL"))
    private void cshudcraft$held(PlayerLikeEntity entity, PlayerEntityRenderState state, float tickProgress, CallbackInfo ci) {
        String held = entity instanceof PlayerEntity p ? ThirdPersonWeapon.heldOf(p) : "";
        state.setData(ThirdPersonWeapon.HELD, held);
        if (held.isEmpty()) return;
        state.rightHandItemState.clear();
        state.leftHandItemState.clear();
        BipedEntityModel.ArmPose[] poses = ThirdPersonWeapon.poses(held);
        state.rightArmPose = poses[0];
        state.leftArmPose = poses[1];
        if (DEBUG && gg.melty.cshudcraft.TestMode.poseSide()) { // dev pose check: turn the model sideways to the camera
            state.bodyYaw += 90f;
            state.relativeHeadYaw = 0f;
        }
    }
}
