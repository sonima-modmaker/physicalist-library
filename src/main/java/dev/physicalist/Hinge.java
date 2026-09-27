package dev.physicalist;

/**
 * A generic, bounded hinge. The owning mod can persist position/velocity and use
 * position to rotate one of its collision/model parts.
 */
public final class Hinge {
    private double position;
    private double velocity;
    public Hinge(double initial) { position = Math.clamp(initial, 0, 1); }
    public double position() { return position; }
    public double velocity() { return velocity; }
    public void setPosition(double value) { position = Math.clamp(value, 0, 1); }
    public void setVelocity(double value) { velocity = Double.isFinite(value) ? value : 0; }
    public void step(double appliedTorque, double restPosition, double stiffness,
                     double damping, double resistance, double maxRate) {
        if (!Double.isFinite(appliedTorque) || !Double.isFinite(restPosition)
                || !Double.isFinite(stiffness) || !Double.isFinite(damping)
                || !Double.isFinite(resistance) || !Double.isFinite(maxRate)
                || maxRate <= 0) throw new IllegalArgumentException("Invalid hinge parameter");
        double force = appliedTorque + (Math.clamp(restPosition, 0, 1) - position) * Math.max(0, stiffness);
        if (Math.abs(force) < Math.max(0, resistance)) force = 0;
        else force -= Math.copySign(Math.max(0, resistance), force);
        velocity = Math.clamp((velocity + force) * Math.clamp(damping, 0, 1), -maxRate, maxRate);
        double next = Math.clamp(position + velocity, 0, 1);
        if (next == 0 || next == 1) velocity = 0;
        position = next;
    }
}
