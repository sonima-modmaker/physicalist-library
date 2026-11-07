package dev.physicalist;

import net.minecraft.world.phys.Vec3;

/** Authored lifting surface in body-local metres (+Z forward). Coefficients use SI units. */
public record AeroSurface(Vec3 point, Vec3 chord, Vec3 span, double area,
        double liftSlope, double maximumLift, double zeroLiftDrag,
        double aspectRatio, double efficiency, double stallRadians, double incidenceRadians) {
    public AeroSurface {
        if(!RigidBodyState.finite(point)||!RigidBodyState.finite(chord)||!RigidBodyState.finite(span)
                ||chord.lengthSqr()<1e-12||span.lengthSqr()<1e-12||chord.cross(span).lengthSqr()<1e-8
                ||!Double.isFinite(area)||area<=0||!Double.isFinite(liftSlope)||liftSlope<0
                ||!Double.isFinite(maximumLift)||maximumLift<0||!Double.isFinite(zeroLiftDrag)||zeroLiftDrag<0
                ||!Double.isFinite(aspectRatio)||aspectRatio<=0||!Double.isFinite(efficiency)||efficiency<=0||efficiency>1
                ||!Double.isFinite(stallRadians)||stallRadians<=0||stallRadians>=Math.PI/2
                ||!Double.isFinite(incidenceRadians)||Math.abs(incidenceRadians)>Math.PI/4)
            throw new IllegalArgumentException("Invalid aerodynamic surface");
        chord=chord.scale(1/chord.length());
        span=span.subtract(chord.scale(span.dot(chord)));
        span=span.scale(1/span.length());
    }
    public static AeroSurface wing(Vec3 point,Vec3 chord,Vec3 span,double area,double aspectRatio,double incidenceDegrees){
        return new AeroSurface(point,chord,span,area,5.2,1.25,.025,aspectRatio,.8,Math.toRadians(18),Math.toRadians(incidenceDegrees));
    }
}
