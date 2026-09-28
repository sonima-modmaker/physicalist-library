package dev.physicalist;

/** An entity whose rendering and collision geometry both respond to scale. */
public interface PhysicalistScalable {
    double physicalistScale();
    void setPhysicalistScale(double scale);
}
