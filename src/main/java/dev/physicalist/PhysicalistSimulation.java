package dev.physicalist;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Optional server-side compound-body integrator. Call once per server tick from an
 * adapter that suppresses the entity's ordinary movement for that tick.
 */
public final class PhysicalistSimulation {
    private record Obstacle(AABB bounds, CompoundCollision.Box box) {}

    public static void step(PhysicsBody body) {
        Entity entity = body.entity();
        if (!(entity.level() instanceof ServerLevel level) || entity.isRemoved()) return;
        PhysicalistBodies.track(body);
        PhysicsProfile p = body.profile();
        List<CompoundCollision.Box> initialBoxes = List.copyOf(body.collisionBoxes());
        Aerodynamics.Step air = body.automaticAerodynamics()
                ? AutoAerodynamics.step(entity.getDeltaMovement(), body.angularVelocity(),
                        body.forward(), body.up(), entity.position(), initialBoxes,
                        body.aerodynamicMass(), body.damaged(), p, body.autoAeroProfile())
                : Aerodynamics.step(entity.getDeltaMovement(), body.angularVelocity(),
                        body.forward(), body.up(), body.wingArea(), body.wingImbalance(),
                        body.stabilizers(), body.damaged(), p);
        Vec3 velocity = air.velocity();
        if (velocity.length() > p.maximumSpeed()) velocity = velocity.normalize().scale(p.maximumSpeed());
        Vec3 angular = air.angular();
        entity.setNoGravity(true);
        entity.noPhysics = true;

        double sweepRadius = Math.max(.5, entity.getBoundingBox().getSize() * .5);
        for (CompoundCollision.Box box : initialBoxes) {
            double[] half = box.half();
            double partRadius = Math.sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2]);
            sweepRadius = Math.max(sweepRadius, entity.position().distanceTo(box.center()) + partRadius);
        }
        int steps = Math.clamp((int) Math.ceil((velocity.length() + angular.length() * sweepRadius)
                / p.substepDistance()), 1, p.maximumSubsteps());
        double dt = 1.0 / steps;
        Vec3 origin = entity.position();
        AABB route = pathBounds(origin, origin.add(velocity), sweepRadius);
        boolean loadedPath = level.hasChunksAt((int) Math.floor(route.minX), (int) Math.floor(route.minZ),
                (int) Math.floor(route.maxX), (int) Math.floor(route.maxZ));
        List<Obstacle> cachedObstacles = loadedPath
                ? obstacles(level, entity, route) : null;
        if (cachedObstacles != null && clearCorridor(initialBoxes, origin, velocity, angular, cachedObstacles)) {
            entity.setPos(origin.add(velocity));
            body.rotate(angular, 1);
            entity.setDeltaMovement(velocity);
            body.setAngularVelocity(angular);
            entity.hasImpulse = true;
            return;
        }
        for (int substep = 0; substep < steps && !entity.isRemoved(); substep++) {
            Vec3 before = entity.position();
            List<CompoundCollision.Box> oldBoxes = substep == 0 ? initialBoxes : List.copyOf(body.collisionBoxes());
            Vec3 travel = velocity.scale(dt);
            Vec3 next = before.add(travel);
            if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(next))) {
                velocity = Vec3.ZERO;
                break;
            }
            entity.setPos(next);
            body.rotate(angular, dt);
            List<CompoundCollision.Box> newBoxes = List.copyOf(body.collisionBoxes());
            if (newBoxes.size() != oldBoxes.size())
                throw new IllegalStateException("Physicalist body changed part count during a substep");

            List<Obstacle> nearby = cachedObstacles != null ? cachedObstacles
                    : obstacles(level, entity, pathBounds(before, next, sweepRadius));
            CompoundCollision.Contact best = null;
            int bestPart = -1;
            for (int part = 0; part < newBoxes.size(); part++) {
                var end = newBoxes.get(part);
                var start = oldBoxes.get(part);
                double[] half = start.half();
                double partRadius = Math.sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2]);
                double rotationMargin = partRadius * Math.min(2, angular.length() * dt);
                AABB sweptBounds = bounds(start).minmax(bounds(end))
                        .minmax(bounds(start).move(end.center().subtract(start.center())))
                        .inflate(rotationMargin + 1e-4);
                for (Obstacle obstacle : nearby) {
                    if (!sweptBounds.intersects(obstacle.bounds())) continue;
                    var world = obstacle.box();
                    var hit = CompoundCollision.contact(end, world);
                    if (hit == null) hit = CompoundCollision.swept(start, end, world);
                    if (hit != null && (best == null || hit.depth() > best.depth())) {
                        best = hit;
                        bestPart = part;
                    }
                }
            }
            if (best != null) {
                Vec3 normal = best.normal();
                entity.setPos(entity.position().add(normal.scale(best.depth() + p.contactSlop())));
                Vec3 arm = best.point().subtract(entity.position());
                double mass = Math.max(.1, body.aerodynamicMass());
                double inverseMass = 1 / mass;
                AABB bounds = entity.getBoundingBox();
                double inertia = Math.max(.5, mass * (bounds.getXsize() * bounds.getXsize()
                        + bounds.getYsize() * bounds.getYsize()
                        + bounds.getZsize() * bounds.getZsize()) / 12);
                double inverseInertia = 1 / inertia;
                double closing = velocity.add(angular.cross(arm)).dot(normal);
                double inward = Math.max(0, -closing);
                if (inward >= p.minimumImpactSpeed()) {
                    body.onImpact(bestPart, best, inward);
                    // Impact handlers may destroy blocks; refresh the cache before another substep.
                    cachedObstacles = null;
                }
                if (inward > 0) {
                    double impulseSize = inward * (1 + p.restitution())
                            / (inverseMass + arm.cross(normal).lengthSqr() * inverseInertia);
                    Vec3 impulse = normal.scale(impulseSize);
                    velocity = velocity.add(impulse.scale(inverseMass));
                    angular = angular.add(arm.cross(impulse).scale(inverseInertia));
                    Vec3 tangent = velocity.add(angular.cross(arm));
                    tangent = tangent.subtract(normal.scale(tangent.dot(normal)));
                    if (tangent.lengthSqr() > 1e-10) {
                        Vec3 direction = tangent.normalize();
                        double frictionSize = Math.min(impulseSize * p.surfaceFriction(),
                                tangent.length() / (inverseMass
                                        + arm.cross(direction).lengthSqr() * inverseInertia));
                        Vec3 friction = direction.scale(-frictionSize);
                        velocity = velocity.add(friction.scale(inverseMass));
                        angular = angular.add(arm.cross(friction).scale(inverseInertia));
                    }
                }
            }
        }
        entity.setDeltaMovement(velocity);
        body.setAngularVelocity(angular);
        entity.hasImpulse = true;
    }

    private static AABB pathBounds(Vec3 start, Vec3 end, double radius) {
        return new AABB(Math.min(start.x, end.x) - radius - .25,
                Math.min(start.y, end.y) - radius - .25,
                Math.min(start.z, end.z) - radius - .25,
                Math.max(start.x, end.x) + radius + .25,
                Math.max(start.y, end.y) + radius + .25,
                Math.max(start.z, end.z) + radius + .25);
    }

    private static AABB bounds(CompoundCollision.Box box) {
        Vec3[] axes = box.axes();
        double[] half = box.half();
        double x = Math.abs(axes[0].x * half[0]) + Math.abs(axes[1].x * half[1]) + Math.abs(axes[2].x * half[2]);
        double y = Math.abs(axes[0].y * half[0]) + Math.abs(axes[1].y * half[1]) + Math.abs(axes[2].y * half[2]);
        double z = Math.abs(axes[0].z * half[0]) + Math.abs(axes[1].z * half[1]) + Math.abs(axes[2].z * half[2]);
        Vec3 c = box.center();
        return new AABB(c.x - x, c.y - y, c.z - z, c.x + x, c.y + y, c.z + z);
    }

    private static boolean clearCorridor(List<CompoundCollision.Box> parts, Vec3 origin, Vec3 travel, Vec3 angular,
                                         List<Obstacle> obstacles) {
        if (obstacles.isEmpty()) return true;
        double turn = angular.length();
        if (turn > Math.PI) return false;
        for (CompoundCollision.Box part : parts) {
            double[] half = part.half();
            double radius = origin.distanceTo(part.center())
                    + Math.sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2]);
            AABB start = bounds(part);
            AABB corridor = start.minmax(start.move(travel)).inflate(radius * turn + 1e-4);
            for (Obstacle obstacle : obstacles) {
                if (corridor.intersects(obstacle.bounds())) return false;
            }
        }
        return true;
    }

    private static List<Obstacle> obstacles(ServerLevel level, Entity entity, AABB search) {
        List<Obstacle> result = new ArrayList<>();
        for (var shape : level.getBlockCollisions(entity, search)) {
            for (AABB block : shape.toAabbs()) result.add(new Obstacle(block, CompoundCollision.box(block)));
        }
        return result;
    }

    private PhysicalistSimulation() {}
}
