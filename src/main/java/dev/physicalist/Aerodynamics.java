package dev.physicalist;

import net.minecraft.world.phys.Vec3;

/** Airflow-based lift, drag and restoring moments, independent of any weapon. */
public final class Aerodynamics {
    public record Step(Vec3 velocity, Vec3 angular) {}

    public static Step step(Vec3 velocity, Vec3 angular, Vec3 forward, Vec3 up,
                            double wingArea, double imbalance, double stabilizers,
                            boolean bodyDamaged, PhysicsProfile p) {
        double speed = velocity.length();
        if (speed < 1e-5)
            return new Step(velocity.add(0, -p.gravity(), 0), angular.scale(p.angularDamping()));
        Vec3 air = velocity.scale(1 / speed);
        double alignment = forward.dot(air);
        double crossflow = Math.max(0, 1 - alignment * alignment);
        double drag = Math.min(.10, p.linearDrag() + speed * (p.speedDrag()
                + crossflow * p.crossflowDrag()) + (bodyDamaged ? p.damageDrag() : 0));
        Vec3 lift = up.subtract(air.scale(up.dot(air))).scale(
                Math.min(p.maximumLift(), speed * speed * p.liftFactor())
                        * Math.max(0, wingArea) * Math.max(0, alignment));
        Vec3 torque = forward.cross(air).scale(Math.min(p.maximumRestoringTorque(),
                speed * p.restoringTorque()) * (bodyDamaged ? .25 : 1)
                * (.1 + .9 * Math.max(0, stabilizers)));
        if (!bodyDamaged && Math.abs(imbalance) < .01 && wingArea > .9) {
            Vec3 horizonUp = new Vec3(0, 1, 0).subtract(air.scale(air.y));
            if (horizonUp.lengthSqr() > .01)
                torque = torque.add(up.cross(horizonUp.normalize()).scale(
                        Math.min(p.maximumHorizonTorque(), speed * p.horizonTorque())));
        }
        torque = torque.add(forward.scale(imbalance * Math.min(
                p.maximumImbalanceTorque(), speed * p.imbalanceTorque())));
        return new Step(velocity.scale(1 - drag).add(lift).add(0, -p.gravity(), 0),
                angular.scale(p.angularDamping()).add(torque));
    }

    private Aerodynamics() {}
}
