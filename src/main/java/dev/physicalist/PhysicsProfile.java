package dev.physicalist;

/**
 * All coefficients are expressed per Minecraft tick. Other mods may create a profile
 * per entity or per vehicle type; the global config supplies the default profile.
 */
public record PhysicsProfile(
        double gravity, double linearDrag, double speedDrag, double crossflowDrag, double damageDrag,
        double liftFactor, double maximumLift, double restoringTorque,
        double maximumRestoringTorque, double horizonTorque, double maximumHorizonTorque,
        double imbalanceTorque, double maximumImbalanceTorque, double angularDamping,
        double restitution, double surfaceFriction, double maximumSpeed,
        double contactSlop, double minimumImpactSpeed, double substepDistance,
        int maximumSubsteps) {
    public PhysicsProfile {
        if (!Double.isFinite(gravity) || gravity < 0 || !Double.isFinite(linearDrag) || linearDrag < 0
                || !Double.isFinite(speedDrag) || speedDrag < 0
                || !Double.isFinite(crossflowDrag) || crossflowDrag < 0
                || !Double.isFinite(damageDrag) || damageDrag < 0
                || !Double.isFinite(liftFactor) || liftFactor < 0
                || !Double.isFinite(maximumLift) || maximumLift < 0
                || !Double.isFinite(restoringTorque) || restoringTorque < 0
                || !Double.isFinite(maximumRestoringTorque) || maximumRestoringTorque < 0
                || !Double.isFinite(horizonTorque) || horizonTorque < 0
                || !Double.isFinite(maximumHorizonTorque) || maximumHorizonTorque < 0
                || !Double.isFinite(imbalanceTorque) || imbalanceTorque < 0
                || !Double.isFinite(maximumImbalanceTorque) || maximumImbalanceTorque < 0
                || !Double.isFinite(angularDamping) || angularDamping < 0 || angularDamping > 1
                || !Double.isFinite(restitution) || restitution < 0 || restitution > 1
                || !Double.isFinite(surfaceFriction) || surfaceFriction < 0 || surfaceFriction > 1
                || !Double.isFinite(maximumSpeed) || maximumSpeed <= 0
                || !Double.isFinite(contactSlop) || contactSlop < 0
                || !Double.isFinite(minimumImpactSpeed) || minimumImpactSpeed < 0
                || !Double.isFinite(substepDistance) || substepDistance <= 0
                || maximumSubsteps < 1 || maximumSubsteps > 512)
            throw new IllegalArgumentException("Invalid Physicalist physics profile");
    }
}
