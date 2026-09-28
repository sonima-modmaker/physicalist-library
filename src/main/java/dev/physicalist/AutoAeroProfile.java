package dev.physicalist;

/** Per-body settings for aerodynamic surfaces inferred from collision boxes. */
public record AutoAeroProfile(
        double maximumThicknessRatio, double minimumArea, double minimumAspectRatio,
        double incidenceDegrees, double liftCoefficient, double maximumLift,
        double plateDragCoefficient, double maximumPlateDrag,
        double torqueCoefficient, double maximumTorque) {
    public AutoAeroProfile {
        if (!Double.isFinite(maximumThicknessRatio) || maximumThicknessRatio <= 0 || maximumThicknessRatio > 1
                || !Double.isFinite(minimumArea) || minimumArea < 0
                || !Double.isFinite(minimumAspectRatio) || minimumAspectRatio < 1
                || !Double.isFinite(incidenceDegrees) || incidenceDegrees < -30 || incidenceDegrees > 30
                || !Double.isFinite(liftCoefficient) || liftCoefficient < 0
                || !Double.isFinite(maximumLift) || maximumLift < 0
                || !Double.isFinite(plateDragCoefficient) || plateDragCoefficient < 0
                || !Double.isFinite(maximumPlateDrag) || maximumPlateDrag < 0
                || !Double.isFinite(torqueCoefficient) || torqueCoefficient < 0
                || !Double.isFinite(maximumTorque) || maximumTorque < 0)
            throw new IllegalArgumentException("Invalid Physicalist automatic aerodynamics profile");
    }
}
