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
        double distance = range * range;
        for (Entity candidate : player.level().getEntities(player, search,
                entity -> PhysicalistBodies.find(entity) != null)) {
            var intersection = candidate.getBoundingBox().inflate(.15).clip(start, end);
            if (intersection.isEmpty()) continue;
            double squared = intersection.get().distanceToSqr(start);
            if (squared < distance) {
                nearest = candidate;
                distance = squared;
            }
        }
        return nearest;
    }
    private PhysicalistTargeting() {}
}
