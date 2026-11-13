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
    public static final ModConfigSpec.DoubleValue GRAVITY = d("motion.gravity", .024525, 2);
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
    public static final ModConfigSpec.DoubleValue AUTO_THICKNESS = B.defineInRange("autoAero.maximumThicknessRatio", .18, .001, 1);
    public static final ModConfigSpec.DoubleValue AUTO_AREA = d("autoAero.minimumArea", .2, 10000);
    public static final ModConfigSpec.DoubleValue AUTO_ASPECT = B.defineInRange("autoAero.minimumAspectRatio", 1.2, 1, 100);
    public static final ModConfigSpec.DoubleValue AUTO_INCIDENCE = B.defineInRange("autoAero.incidenceDegrees", 5.0, -30.0, 30.0);
    public static final ModConfigSpec.DoubleValue AUTO_LIFT = d("autoAero.liftCoefficient", .012, 10);
    public static final ModConfigSpec.DoubleValue AUTO_MAX_LIFT = d("autoAero.maximumLift", .24, 10);
    public static final ModConfigSpec.DoubleValue AUTO_PLATE_DRAG = d("autoAero.plateDragCoefficient", .008, 10);
    public static final ModConfigSpec.DoubleValue AUTO_MAX_DRAG = d("autoAero.maximumPlateDrag", .20, 10);
    public static final ModConfigSpec.DoubleValue AUTO_TORQUE = d("autoAero.torqueCoefficient", .15, 10);
    public static final ModConfigSpec.DoubleValue AUTO_MAX_TORQUE = d("autoAero.maximumTorque", .03, 10);
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
    public static final ModConfigSpec.BooleanValue WATER_ENABLED = B.comment("Archimedes buoyancy and water drag for physical bodies (native engine).").define("water.enabled", true);
    public static final ModConfigSpec.DoubleValue WATER_BUOYANCY = B.comment("Multiplier on buoyant force. 1 = physical; above 1 makes everything float higher.").defineInRange("water.buoyancyMultiplier", 1.0, 0, 20);
    public static final ModConfigSpec.BooleanValue WATER_FLOAT_ALL = B.comment("Make every physical body float, whatever it is made of.").define("water.floatEverything", false);
    public static final ModConfigSpec.DoubleValue WATER_FLOAT_ALL_DENSITY = B.comment("Density (relative to water) used for heavier bodies when floatEverything is on. Below 1 floats; 0.5 floats half-submerged.").defineInRange("water.floatEverythingDensity", .7, .05, .99);
    public static final ModConfigSpec.DoubleValue WATER_RAGDOLL_DENSITY = B.comment("Ragdoll density relative to water. Below 1 floats (people ~0.95), above 1 sinks.").defineInRange("water.ragdollDensity", .85, .05, 20);
    public static final ModConfigSpec.DoubleValue WATER_CHAIN_DENSITY = B.comment("Chain link density relative to water (iron ~7.8).").defineInRange("water.chainDensity", 7.8, .05, 20);
    public static final ModConfigSpec.DoubleValue WATER_DEFAULT_DENSITY = B.comment("Density for bodies from other mods that do not specify one. Physical blocks use their material (wood floats, stone and metal sink).").defineInRange("water.defaultDensity", 1.5, .05, 20);
    public static final ModConfigSpec.DoubleValue WATER_LINEAR_DRAG = B.comment("Water resistance to movement, per second, when fully submerged.").defineInRange("water.linearDrag", 1.5, 0, 20);
    public static final ModConfigSpec.DoubleValue WATER_ANGULAR_DRAG = B.comment("Water resistance to rotation, per second, when fully submerged.").defineInRange("water.angularDrag", 2.0, 0, 20);
    public static final ModConfigSpec.BooleanValue RAGDOLL_ON_DEATH = B.comment("Mobs (and players) that die turn into ragdolls, keeping the push of the killing blow. Also /physicalist ragdoll ondeath.").define("ragdolls.onDeath", false);
    public static final ModConfigSpec.BooleanValue RAGDOLL_ON_DEATH_PLAYERS = B.comment("With ragdolls.onDeath, also leave a ragdoll where a player dies.").define("ragdolls.onDeathPlayers", true);
    public static final ModConfigSpec.IntValue RAGDOLL_LIFETIME = B.comment("Seconds before a ragdoll disappears.").defineInRange("ragdolls.lifetimeSeconds", 600, 5, 36000);
    public static final ModConfigSpec.BooleanValue EXPERIMENTAL_SOFT_BODIES = B.comment("Experimental: soft bodies (the Physicalist tool's menu: deformable cars and planes from model files). Off by default.").define("experimental.softBodies", false);
    public static boolean softBodies() {
        try { return EXPERIMENTAL_SOFT_BODIES.get(); } catch (IllegalStateException notLoaded) { return false; }
    }
    public static final ModConfigSpec.BooleanValue NATIVE_ENABLED = B.comment("Use the shared native Rapier scene when a compatible native binary is bundled.").define("engine.nativeEnabled",true);
    public static final ModConfigSpec SPEC = B.build();
    private static volatile PhysicsProfile cached;
    private static volatile AutoAeroProfile cachedAutoAero;
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
    public static AutoAeroProfile autoAeroDefaults() {
        AutoAeroProfile current = cachedAutoAero;
        if (current != null) return current;
        current = new AutoAeroProfile(AUTO_THICKNESS.get(), AUTO_AREA.get(), AUTO_ASPECT.get(),
                AUTO_INCIDENCE.get(), AUTO_LIFT.get(), AUTO_MAX_LIFT.get(),
                AUTO_PLATE_DRAG.get(), AUTO_MAX_DRAG.get(), AUTO_TORQUE.get(), AUTO_MAX_TORQUE.get());
        cachedAutoAero = current;
        return current;
    }
    @SubscribeEvent public static void onLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getModId().equals(PhysicalistLibrary.MOD_ID)) {
            cached = null;
            cachedAutoAero = null;
        }
    }
    @SubscribeEvent public static void onReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(PhysicalistLibrary.MOD_ID)) {
            cached = null;
            cachedAutoAero = null;
        }
    }
    private PhysicalistConfig() {}
}

