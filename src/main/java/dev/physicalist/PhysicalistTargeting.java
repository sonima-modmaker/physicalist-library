package dev.physicalist;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server-side ray selection shared by the wand and deleter. */
public final class PhysicalistTargeting {
    public static Entity aimedBody(Player player, double range) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(range));
        AABB search = player.getBoundingBox().expandTowards(player.getLookAngle().scale(range)).inflate(2);
        Entity nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Entity candidate : player.level().getEntities(player, search,
                entity -> PhysicalistBodies.find(entity) != null)) {
            PhysicsBody body = PhysicalistBodies.find(candidate);
            if (body == null) continue;
            for (CompoundCollision.Box box : body.collisionBoxes()) {
                double hit = rayFraction(box, start, end);
                if (hit >= distance) continue;
                nearest = candidate;
                distance = hit;
            }
        }
        return nearest;
    }

    /** Ray against the same oriented parts used by the collision solver. */
    static double rayFraction(CompoundCollision.Box box, Vec3 start, Vec3 end) {
        Vec3 offset = start.subtract(box.center());
        Vec3 direction = end.subtract(start);
        double near = 0, far = 1;
        for (int i = 0; i < 3; i++) {
            double origin = offset.dot(box.axes()[i]);
            double speed = direction.dot(box.axes()[i]);
            double radius = box.half()[i];
            if (radius <= 0) return Double.POSITIVE_INFINITY;
            if (Math.abs(speed) < 1e-10) {
                if (Math.abs(origin) > radius) return Double.POSITIVE_INFINITY;
                continue;
            }
            double a = (-radius - origin) / speed;
            double b = (radius - origin) / speed;
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) return Double.POSITIVE_INFINITY;
        }
        return near;
    }
    private PhysicalistTargeting() {}
}
