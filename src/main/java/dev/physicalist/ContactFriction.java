package dev.physicalist;

import net.minecraft.world.phys.Vec3;

/** Coulomb friction at the actual compound-body contact, including angular motion. */
public final class ContactFriction {
    public static void apply(PhysicsBody a, PhysicsBody b, Vec3 point, Vec3 normal,
                             double normalImpulse, double fraction) {
        double ma = Math.max(.1, a.aerodynamicMass()), mb = Math.max(.1, b.aerodynamicMass());
        Vec3 ra = point.subtract(a.centerOfMass()), rb = point.subtract(b.centerOfMass());
        Vec3 relative = a.entity().getDeltaMovement().add(a.angularVelocity().cross(ra))
                .subtract(b.entity().getDeltaMovement()).subtract(b.angularVelocity().cross(rb));
        Vec3 tangent = relative.subtract(normal.scale(relative.dot(normal)));
        double speed = tangent.length();
        if (speed < 1e-7) return;
        Vec3 direction = tangent.scale(1 / speed);
        double ia = inertia(a, ma), ib = inertia(b, mb);
        double inverse = 1 / ma + 1 / mb
                + ra.cross(direction).lengthSqr() / ia + rb.cross(direction).lengthSqr() / ib;
        double load = normalImpulse + Math.abs(normal.y)
                * Math.min(a.profile().gravity(), b.profile().gravity()) * fraction / (1 / ma + 1 / mb);
        double limit = Math.min(a.profile().surfaceFriction(), b.profile().surfaceFriction()) * load;
        Vec3 impulse = direction.scale(-Math.min(speed / inverse, limit));
        a.entity().setDeltaMovement(a.entity().getDeltaMovement().add(impulse.scale(1 / ma)));
        b.entity().setDeltaMovement(b.entity().getDeltaMovement().subtract(impulse.scale(1 / mb)));
        a.setAngularVelocity(a.angularVelocity().add(ra.cross(impulse).scale(1 / ia)));
        b.setAngularVelocity(b.angularVelocity().subtract(rb.cross(impulse).scale(1 / ib)));
        a.entity().hasImpulse = true;
        b.entity().hasImpulse = true;
    }

    private static double inertia(PhysicsBody body, double mass) {
        var bounds = body.entity().getBoundingBox();
        return Math.max(.5, mass * (bounds.getXsize() * bounds.getXsize()
                + bounds.getYsize() * bounds.getYsize() + bounds.getZsize() * bounds.getZsize()) / 12);
    }
    private ContactFriction() {}
}
