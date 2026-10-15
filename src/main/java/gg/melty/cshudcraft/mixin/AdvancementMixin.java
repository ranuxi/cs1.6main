package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.CsServer;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A finished, visible Minecraft advancement counts as a completed task (economy.json "advancement"). */
@Mixin(PlayerAdvancementTracker.class)
public abstract class AdvancementMixin {
    @Shadow private ServerPlayerEntity owner;

    @Inject(method = "grantCriterion", at = @At("RETURN"))
    private void cshudcraft$task(AdvancementEntry advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || owner == null) return;
        if (advancement.value().display().isEmpty()) return;
        if (((PlayerAdvancementTracker) (Object) this).getProgress(advancement).isDone()) CsServer.onAdvancement(owner);
    }
}
