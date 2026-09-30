package dev.physicalist.mixin;

import dev.physicalist.PhysicalistWalkingCollision;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Includes physical parts in vanilla movement and applies blocked player pushes. */
@Mixin(Entity.class)
public abstract class EntityWalkingCollisionMixin {
    @Inject(method = "collide", at = @At("RETURN"))
    private void physicalist$pushBlockedBody(Vec3 wanted, CallbackInfoReturnable<Vec3> result) {
        Entity entity = (Entity) (Object) this;
        if (!(entity instanceof Player player) || player.isSpectator()) return;
        PhysicalistWalkingCollision.pushBlocked(player, wanted, result.getReturnValue());
    }

    @Inject(method = "collectColliders", at = @At("RETURN"), cancellable = true)
    private static void physicalist$assemblyColliders(Entity entity, Level level, List<VoxelShape> original,
                                                       AABB bounds, CallbackInfoReturnable<List<VoxelShape>> result) {
        result.setReturnValue(PhysicalistWalkingCollision.add(result.getReturnValue(), entity,
                Vec3.ZERO, bounds, level));
    }
}
