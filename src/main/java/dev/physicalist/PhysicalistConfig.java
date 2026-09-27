package dev.physicalist;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

/** Common config values are defaults; an adapter may supply its own profile. */
@EventBusSubscriber(modid = PhysicalistLibrary.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class PhysicalistConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();
    private static ModConfigSpec.DoubleValue d(String key, double value, double max) {
        return B.defineInRange(key, value, 0, max);
    }
    public static final ModConfigSpec.DoubleValue GRAVITY = d("motion.gravity", .045, 2);
    public static final ModConfigSpec.DoubleValue DRAG = d("aerodynamics.linearDrag", .002, 1);
    public static final ModConfigSpec.DoubleValue SPEED_DRAG = d("aerodynamics.speedDrag", .0006, 1);
    public static final ModConfigSpec.DoubleValue CROSS_DRAG = d("aerodynamics.crossflowDrag", .014, 1);
    public static final ModConfigSpec.DoubleValue DAMAGE_DRAG = d("aerodynamics.damageDrag", .008, 1);
    public static final ModConfigSpec.DoubleValue LIFT = d("aerodynamics.liftFactor", .003, 1);
    public static final ModConfigSpec.DoubleValue MAX_LIFT = d("aerodynamics.maximumLift", .038, 2);
    public static final ModConfigSpec.DoubleValue RESTORE = d("aerodynamics.restoringTorque", .0014, 1);
    public static final ModConfigSpec.DoubleValue MAX_RESTORE = d("aerodynamics.maximumRestoringTorque", .010, 1);
    public static final ModConfigSpec.DoubleValue HORIZON = d("aerodynamics.horizonTorque", .0005, 1);
    public static final ModConfigSpec.DoubleValue MAX_HORIZON = d("aerodynamics.maximumHorizonTorque", .003, 1);
    public static final ModConfigSpec.DoubleValue IMBALANCE = d("aerodynamics.imbalanceTorque", .0015, 1);
    public static final ModConfigSpec.DoubleValue MAX_IMBALANCE = d("aerodynamics.maximumImbalanceTorque", .009, 1);
    public static final ModConfigSpec.DoubleValue ANGULAR_DAMPING = d("motion.angularDamping", .965, 1);
    public static final ModConfigSpec.DoubleValue RESTITUTION = d("collision.restitution", .12, 1);
    public static final ModConfigSpec.DoubleValue FRICTION = d("collision.friction", .35, 1);
    public static final ModConfigSpec.DoubleValue MAX_SPEED = B.defineInRange("motion.maximumSpeed", 16, .001, 4096);
    public static final ModConfigSpec.DoubleValue CONTACT_SLOP = d("collision.contactSlop", .001, 1);
    public static final ModConfigSpec.DoubleValue MIN_IMPACT = d("collision.minimumImpactSpeed", .35, 100);
    public static final ModConfigSpec.DoubleValue SUBSTEP_DISTANCE = B.defineInRange("collision.substepDistance", .08, .001, 16);
    public static final ModConfigSpec.IntValue MAX_SUBSTEPS = B.defineInRange("collision.maximumSubsteps", 320, 1, 512);
    public static final ModConfigSpec.DoubleValue HINGE_RESISTANCE = d("hinges.contactResistance", .009, 1);
    public static final ModConfigSpec.DoubleValue HINGE_MAX_TRAVEL = d("hinges.maximumTravelPerTick", .025, 1);
    public static final ModConfigSpec.DoubleValue HINGE_IMPULSE_GAIN = d("hinges.impactGain", 1.1, 100);
    public static final ModConfigSpec.DoubleValue HINGE_RETURN_RATE = d("hinges.returnRate", .006, 1);
    public static final ModConfigSpec.DoubleValue HINGE_LATCH_RATE = d("hinges.latchRate", .055, 1);
    public static final ModConfigSpec SPEC = B.build();
    private static volatile PhysicsProfile cached;
    public static PhysicsProfile defaults() {
        PhysicsProfile current = cached;
        if (current != null) return current;
        current = new PhysicsProfile(GRAVITY.get(), DRAG.get(), SPEED_DRAG.get(), CROSS_DRAG.get(), DAMAGE_DRAG.get(),
                LIFT.get(), MAX_LIFT.get(), RESTORE.get(), MAX_RESTORE.get(), HORIZON.get(), MAX_HORIZON.get(), IMBALANCE.get(), MAX_IMBALANCE.get(),
                ANGULAR_DAMPING.get(), RESTITUTION.get(), FRICTION.get(), MAX_SPEED.get(),
                CONTACT_SLOP.get(), MIN_IMPACT.get(), SUBSTEP_DISTANCE.get(), MAX_SUBSTEPS.get());
        cached = current;
        return current;
    }
    @SubscribeEvent public static void onLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getModId().equals(PhysicalistLibrary.MOD_ID)) cached = null;
    }
    @SubscribeEvent public static void onReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(PhysicalistLibrary.MOD_ID)) cached = null;
    }
    private PhysicalistConfig() {}
}
