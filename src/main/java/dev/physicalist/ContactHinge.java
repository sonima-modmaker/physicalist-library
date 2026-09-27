package dev.physicalist;

/** Shared collision-driven folding math. Persist and render the hinge in the owning mod. */
public final class ContactHinge {
    public static float target(float angle, boolean latched, boolean touching) {
        return latched ? Math.min(1, angle + PhysicalistConfig.HINGE_LATCH_RATE.get().floatValue())
                : touching ? angle : Math.max(0, angle - PhysicalistConfig.HINGE_RETURN_RATE.get().floatValue());
    }

    public static float pressureDelta(double normalSpeed, double penetration, double dt, float used) {
        double load = Math.max(0, -normalSpeed) + Math.max(0, penetration) * .5;
        return (float) Math.min(Math.max(0, PhysicalistConfig.HINGE_MAX_TRAVEL.get() - used),
                Math.max(0, load - PhysicalistConfig.HINGE_RESISTANCE.get()) * dt
                        * PhysicalistConfig.HINGE_IMPULSE_GAIN.get());
    }

    private ContactHinge() {}
}
