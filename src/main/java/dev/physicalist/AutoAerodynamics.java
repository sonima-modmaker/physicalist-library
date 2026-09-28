package dev.physicalist;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Infers lifting plates from the current oriented collision geometry. */
public final class AutoAerodynamics {
    private static final double EPSILON = 1e-8;

    /**
     * A flat box whose long direction crosses the body is a lifting surface.
     * Wheels, narrow rods, thick hull sections and zero-volume boxes are ignored.
     */
    public static boolean isLiftingSurface(CompoundCollision.BoxView box, Vec3 forward, AutoAeroProfile settings) {
        return surface(box, forward.normalize(), settings) != null;
    }

    /** One full tick of drag, gravity, geometric lift and off-centre aerodynamic torque. */
    public static Aerodynamics.Step step(Vec3 velocity, Vec3 angular, Vec3 forward, Vec3 up,
                                         Vec3 center, List<? extends CompoundCollision.BoxView> boxes,
                                         double mass, boolean bodyDamaged,
                                         PhysicsProfile physics, AutoAeroProfile settings) {
        if (!Double.isFinite(mass) || mass <= 0) throw new IllegalArgumentException("Mass must be positive");
        if (forward.lengthSqr() < EPSILON || up.lengthSqr() < EPSILON)
            throw new IllegalArgumentException("Forward and up must be nonzero");
        double speed = velocity.length();
        double baseDrag = Math.min(.10, physics.linearDrag() + speed * physics.speedDrag()
                + (bodyDamaged ? physics.damageDrag() : 0));
        Vec3 changedVelocity = velocity.scale(1 - baseDrag).add(0, -physics.gravity(), 0);
        Vec3 changedAngular = angular.scale(physics.angularDamping());
        if (speed < EPSILON || boxes.isEmpty()) return new Aerodynamics.Step(changedVelocity, changedAngular);

        Vec3 flight = velocity.scale(1 / speed);
        Vec3 ahead = forward.normalize();
        Vec3 top = up.normalize();
        Vec3 lift = Vec3.ZERO, drag = Vec3.ZERO;
        Vec3 liftTorque = Vec3.ZERO, dragTorque = Vec3.ZERO;
        for (CompoundCollision.BoxView box : boxes) {
            Surface plate = surface(box, ahead, settings);
            if (plate == null) continue;
            Vec3 normal = plate.normal();
            // Body-relative incidence gives a level wing a small positive angle of attack.
            boolean horizontalWing = Math.abs(normal.dot(top)) >= .5;
            if (horizontalWing && normal.dot(top) < 0) normal = normal.scale(-1);
            Vec3 effectiveNormal = horizontalWing
                    ? normal.subtract(plate.chord().scale(Math.tan(Math.toRadians(settings.incidenceDegrees())))).normalize()
                    : normal;
            double normalFlow = velocity.dot(effectiveNormal);
            // Resolve lift perpendicular to travel. A plate falling face-on instead gains form drag.
            Vec3 liftDirection = normal.subtract(flight.scale(normal.dot(flight)));
            Vec3 plateLift = liftDirection.scale(-normalFlow * speed
                    * settings.liftCoefficient() * plate.area() / mass);
            Vec3 plateDrag = flight.scale(-speed * Math.abs(normalFlow)
                    * settings.plateDragCoefficient() * plate.area() / mass);
            lift = lift.add(plateLift);
            drag = drag.add(plateDrag);
            Vec3 arm = box.center().subtract(center);
            liftTorque = liftTorque.add(arm.cross(plateLift));
            dragTorque = dragTorque.add(arm.cross(plateDrag));
        }
        double liftScale = lift.length() > settings.maximumLift()
                ? settings.maximumLift() / lift.length() : 1;
        double dragScale = drag.length() > settings.maximumPlateDrag()
                ? settings.maximumPlateDrag() / drag.length() : 1;
        lift = lift.scale(liftScale);
        drag = drag.scale(dragScale);
        Vec3 torque = liftTorque.scale(liftScale).add(dragTorque.scale(dragScale))
                .scale(settings.torqueCoefficient());
        if (torque.length() > settings.maximumTorque())
            torque = torque.normalize().scale(settings.maximumTorque());
        return new Aerodynamics.Step(changedVelocity.add(lift).add(drag), changedAngular.add(torque));
    }

    private record Surface(Vec3 normal, Vec3 chord, double area) {}

    private static Surface surface(CompoundCollision.BoxView box, Vec3 forward, AutoAeroProfile settings) {
        Vec3[] axes = box.axes();
        double[] half = box.half();
        if (axes.length != 3 || half.length != 3 || forward.lengthSqr() < EPSILON) return null;
        int thin = 0;
        for (int i = 0; i < 3; i++) {
            if (!Double.isFinite(half[i]) || half[i] <= 0 || axes[i] == null
                    || axes[i].lengthSqr() < EPSILON) return null;
            if (half[i] < half[thin]) thin = i;
        }
        int first = (thin + 1) % 3, second = (thin + 2) % 3;
        if (half[thin] / Math.min(half[first], half[second]) > settings.maximumThicknessRatio()) return null;
        double area = 4 * half[first] * half[second];
        if (area < settings.minimumArea()) return null;
        Vec3 normal = axes[thin].normalize();
        Vec3 chord = forward.subtract(normal.scale(forward.dot(normal)));
        if (chord.lengthSqr() < .25) return null;
        chord = chord.normalize();
        Vec3 span = normal.cross(chord).normalize();
        double chordLength = 2 * (half[first] * Math.abs(axes[first].dot(chord))
                + half[second] * Math.abs(axes[second].dot(chord)));
        double spanLength = 2 * (half[first] * Math.abs(axes[first].dot(span))
                + half[second] * Math.abs(axes[second].dot(span)));
        if (chordLength < EPSILON || spanLength / chordLength < settings.minimumAspectRatio()) return null;
        return new Surface(normal, chord, area);
    }

    private AutoAerodynamics() {}
}
