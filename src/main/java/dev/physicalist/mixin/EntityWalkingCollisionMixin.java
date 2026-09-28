package dev.physicalist.mixin;

import dev.physicalist.PhysicalistWalkingCollision;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Includes library assemblies in both normal contacts and player step collision. */
@Mixin(Entity.class)
public abstract class EntityWalkingCollisionMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void physicalist$walkOnAssemblies(Vec3 wanted, CallbackInfoReturnable<Vec3> result) {
        Entity entity = (Entity) (Object) this;
        if (!(entity instanceof Player player) || player.isSpectator()) return;
        List<VoxelShape> shapes = PhysicalistWalkingCollision.add(List.of(), entity, wanted,
                entity.getBoundingBox(), entity.level());
        if (shapes.isEmpty()) return;
        Vec3 movement = result.getReturnValue();
        AABB box = entity.getBoundingBox();
        double y = Shapes.collide(Direction.Axis.Y, box, shapes, movement.y);
        box = box.move(0, y, 0);
        double x = Shapes.collide(Direction.Axis.X, box, shapes, movement.x);
        box = box.move(x, 0, 0);
        double z = Shapes.collide(Direction.Axis.Z, box, shapes, movement.z);
        result.setReturnValue(new Vec3(x, y, z));
    }

    @Inject(method = "collectColliders", at = @At("RETURN"), cancellable = true)
    private static void physicalist$assemblyColliders(Entity entity, Level level, List<VoxelShape> original,
                                                       AABB bounds, CallbackInfoReturnable<List<VoxelShape>> result) {
        result.setReturnValue(PhysicalistWalkingCollision.add(result.getReturnValue(), entity,
                Vec3.ZERO, bounds, level));
    }
}
