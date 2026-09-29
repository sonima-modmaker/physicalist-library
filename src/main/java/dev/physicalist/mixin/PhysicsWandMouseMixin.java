package dev.physicalist.mixin;

import dev.physicalist.client.PhysicsWandControls;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class PhysicsWandMouseMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void physicalist$rotateHeldBody(CallbackInfo ci) {
        if (!PhysicsWandControls.rotate(accumulatedDX, accumulatedDY)) return;
        accumulatedDX = 0;
        accumulatedDY = 0;
        ci.cancel();
    }
}
