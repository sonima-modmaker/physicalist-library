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
    public static void step(PhysicsBody body) {
        Entity entity = body.entity();
        if (!(entity.level() instanceof ServerLevel level) || entity.isRemoved()) return;
        PhysicsProfile p = body.profile();
        Aerodynamics.Step air = body.automaticAerodynamics()
                ? AutoAerodynamics.step(entity.getDeltaMovement(), body.angularVelocity(),
                        body.forward(), body.up(), entity.position(), body.collisionBoxes(),
                        body.aerodynamicMass(), body.damaged(), p, body.autoAeroProfile())
                : Aerodynamics.step(entity.getDeltaMovement(), body.angularVelocity(),
                        body.forward(), body.up(), body.wingArea(), body.wingImbalance(),
                        body.stabilizers(), body.damaged(), p);
        Vec3 velocity = air.velocity();
        if (velocity.length() > p.maximumSpeed()) velocity = velocity.normalize().scale(p.maximumSpeed());
        Vec3 angular = air.angular();
        entity.setNoGravity(true);
        entity.noPhysics = true;

        double sweepRadius = Math.max(1, entity.getBoundingBox().getSize());
        int steps = Math.clamp((int) Math.ceil((velocity.length() + angular.length() * sweepRadius)
                / p.substepDistance()), 1, p.maximumSubsteps());
        double dt = 1.0 / steps;
        for (int substep = 0; substep < steps && !entity.isRemoved(); substep++) {
            Vec3 before = entity.position();
            List<CompoundCollision.Box> oldBoxes = List.copyOf(body.collisionBoxes());
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

            AABB search = entity.getBoundingBox().move(before.subtract(next))
                    .minmax(entity.getBoundingBox()).inflate(sweepRadius + .25);
            List<AABB> obstacles = new ArrayList<>();
            for (var shape : level.getBlockCollisions(entity, search))
                obstacles.addAll(shape.toAabbs());
            CompoundCollision.Contact best = null;
            int bestPart = -1;
            for (int part = 0; part < newBoxes.size(); part++) {
                var end = newBoxes.get(part);
                var start = oldBoxes.get(part);
                for (AABB obstacle : obstacles) {
                    var world = CompoundCollision.box(obstacle);
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
                double inward = Math.max(0, -velocity.dot(normal));
                if (inward >= p.minimumImpactSpeed()) body.onImpact(bestPart, best, inward);
                velocity = velocity.add(normal.scale(inward * (1 + p.restitution())));
                velocity = velocity.subtract(velocity.subtract(normal.scale(velocity.dot(normal)))
                        .scale(p.surfaceFriction()));
                angular = angular.scale(1 - p.surfaceFriction() * .5);
            }
        }
        entity.setDeltaMovement(velocity);
        body.setAngularVelocity(angular);
        entity.hasImpulse = true;
    }

    private PhysicalistSimulation() {}
}
