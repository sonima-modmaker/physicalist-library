package dev.physicalist;

import net.minecraft.world.entity.Entity;

/**
 * Bodies frozen in place with the wand (left click while holding one). A frozen rigid body stays put as a fixed
 * obstacle that everything still collides with; on a ragdoll only the part that was held is frozen, so the rest of the
 * body hangs from it. Grabbing it with the wand again unfreezes it. Stored in the entity's persistent data, so it
 * survives saving.
 */
public final class PhysicalistFreeze {
    static final String WHOLE = "PhysicalistFrozen", PARTS = "PhysicalistFrozenParts";

    /** Whether the native part (role -1 for a whole body, a limb index for a ragdoll) is frozen. */
    public static boolean frozen(Entity entity, int role) {
        var data = entity.getPersistentData();
        if (entity instanceof PlayerRagdollEntity) return role >= 0 && role < 6 && (data.getInt(PARTS) >> role & 1) != 0;
        return role < 0 && data.getBoolean(WHOLE);
    }
    public static boolean frozen(Entity entity) {
        var data = entity.getPersistentData();
        return data.getBoolean(WHOLE) || data.getInt(PARTS) != 0;
    }

    /** Freezes a whole body, or one part of a ragdoll (part &lt; 0 freezes a ragdoll's torso). */
    public static void freeze(Entity entity, int part) {
        var data = entity.getPersistentData();
        if (entity instanceof PlayerRagdollEntity) data.putInt(PARTS, data.getInt(PARTS) | 1 << (part < 0 || part > 5 ? 1 : part));
        else data.putBoolean(WHOLE, true);
        entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        var body = PhysicalistBodies.find(entity);
        if (body != null && !(entity instanceof PlayerRagdollEntity)) body.setAngularVelocity(net.minecraft.world.phys.Vec3.ZERO);
    }

    /** Lets every frozen part of the entity move again. */
    public static void unfreeze(Entity entity) {
        var data = entity.getPersistentData();
        data.remove(WHOLE);
        data.remove(PARTS);
    }

    private PhysicalistFreeze() {}
}
