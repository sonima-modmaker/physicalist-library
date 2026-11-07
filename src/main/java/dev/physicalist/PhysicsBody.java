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
    /** World-space centre of mass; defaults to the entity anchor for existing adapters. */
    default Vec3 centerOfMass() { return entity().position(); }
    List<CompoundCollision.Box> collisionBoxes();
    Vec3 forward();
    Vec3 up();
    Vec3 angularVelocity();
    void setAngularVelocity(Vec3 angular);

    /** Rotate the model by angular velocity * fraction of a tick. */
    void rotate(Vec3 angular, double fraction);

    /** Stable revision of local collider geometry; -1 asks the scene to compare captured shapes. */
    default long nativeGeometryRevision() { return -1; }
    /** Override for an authoritative quaternion instead of an Euler-angle round trip. */
    default org.joml.Quaternionf nativeOrientation(){return dev.physicalist.nativecore.NativeGeometry.orientation(this);}
    default void applyNativePose(Vec3 position,org.joml.Quaternionf orientation){dev.physicalist.nativecore.NativeGeometry.apply(this,position,orientation);}
    /** Empty infers thin surfaces from collision boxes. Authored points/axes are body-local. */
    default List<AeroSurface> aerodynamicSurfaces(){return List.of();}
    default double airDensity(){return 1.225;}
    /** Ambient wind in blocks/tick. */
    default Vec3 windVelocity(){return Vec3.ZERO;}
    /** Additional world-space SI force/torque, e.g. engines and rotor drives. Applied by native inertia. */
    default SurfaceAerodynamics.Wrench externalWrench(){return SurfaceAerodynamics.Wrench.ZERO;}

    /** False when the integrating mod already supplied this tick's aerodynamic/engine forces. */
    /** Temporarily omit only colliders fully contained in this world region. */
    default net.minecraft.world.phys.AABB ignoredCollisionRegion(){return null;}
    default boolean nativeForcesEnabled() { return true; }
    /** Called after the deferred scene has applied this body's pose and contacts. */
    default void nativeStepCompleted(boolean sleeping) {}
    /** Per-part native contact, including gentle support contacts used by compliant hinges. */
    default void onNativeContact(int part, CompoundCollision.Contact contact, double normalSpeed, double impulse) {
        if (normalSpeed >= profile().minimumImpactSpeed()) onImpact(part, contact, normalSpeed);
    }

    default PhysicsProfile profile() { return PhysicalistConfig.defaults(); }
    /** Geometric aero is enabled by default; override to retain manual wingArea behavior. */
    default boolean automaticAerodynamics() { return true; }
    default AutoAeroProfile autoAeroProfile() { return PhysicalistConfig.autoAeroDefaults(); }
    /** Relative inertia scale: increasing mass reduces lift and plate drag. */
    default double aerodynamicMass() { return 1; }
    /** Density relative to water for buoyancy: below 1 floats, above 1 sinks. */
    default double waterDensity() { return PhysicalistConfig.WATER_DEFAULT_DENSITY.get(); }
    default double wingArea() { return 0; }
    default double wingImbalance() { return 0; }
    default double stabilizers() { return 1; }
    default boolean damaged() { return false; }
    default void onImpact(int part, CompoundCollision.Contact contact, double normalSpeed) {}
}
