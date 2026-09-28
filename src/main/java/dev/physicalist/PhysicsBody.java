package dev.physicalist;

import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Adapter implemented by another mod. The library owns the solver; the adapter owns
 * its model pose, hinges, saved angular velocity, damage and network synchronisation.
 */
public interface PhysicsBody {
    Entity entity();
    List<CompoundCollision.Box> collisionBoxes();
    Vec3 forward();
    Vec3 up();
    Vec3 angularVelocity();
    void setAngularVelocity(Vec3 angular);

    /** Rotate the model by angular velocity * fraction of a tick. */
    void rotate(Vec3 angular, double fraction);

    default PhysicsProfile profile() { return PhysicalistConfig.defaults(); }
    /** Geometric aero is enabled by default; override to retain manual wingArea behavior. */
    default boolean automaticAerodynamics() { return true; }
    default AutoAeroProfile autoAeroProfile() { return PhysicalistConfig.autoAeroDefaults(); }
    /** Relative inertia scale: increasing mass reduces lift and plate drag. */
    default double aerodynamicMass() { return 1; }
    default double wingArea() { return 0; }
    default double wingImbalance() { return 0; }
    default double stabilizers() { return 1; }
    default boolean damaged() { return false; }
    default void onImpact(int part, CompoundCollision.Contact contact, double normalSpeed) {}
}
