package dev.physicalist;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Hold right click to grab any live body registered with the library, by the very point of its collision you aim at:
 * a block by its corner, a ragdoll by its hand (the rest of the body hangs from it). A held body keeps its turn
 * relative to where you face, so it swings round with you; Tab plus the mouse turns it, the wheel moves it nearer or
 * further, and left click freezes it in place (grabbing it again unfreezes it).
 */
public final class PhysicsWandItem extends Item {
    private static final String TARGET = "PhysicalistWandTarget";
    private static final String DISTANCE = "PhysicalistWandDistance";
    /** Held ragdoll part, or -1 for a whole body, or -2 for a target that steers itself (chains). */
    private static final String PART = "PhysicalistWandPart";
    /** Grabbed point in the held body's (or limb's) own frame, and its turn relative to the player's facing. */
    private static final String GRIP = "PhysicalistWandGrip";
    /** Set after freezing: nothing is grabbed again until the right button is let go. */
    private static final String WAIT = "PhysicalistWandWait";
    public PhysicsWandItem(Properties properties) { super(properties); }

    @Override public int getUseDuration(ItemStack stack, LivingEntity user) { return 72000; }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!player.getAbilities().instabuild) return InteractionResultHolder.fail(player.getItemInHand(hand));
        // A new press: whatever was frozen last time may be grabbed again.
        release(player);
        player.getPersistentData().remove(WAIT);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    private static Quaternionf facing(Player player) {
        return new Quaternionf().rotationY((float) Math.toRadians(-player.getYRot()));
    }
    private static Vec3 turn(Quaternionf q, Vec3 v) {
        var r = q.transform(new org.joml.Vector3f((float) v.x, (float) v.y, (float) v.z));
        return new Vec3(r.x, r.y, r.z);
    }
    private static Quaternionf relative(CompoundTag grip) {
        return new Quaternionf(grip.getFloat("QX"), grip.getFloat("QY"), grip.getFloat("QZ"), grip.getFloat("QW")).normalize();
    }

    /**
     * Steering happens in {@link #steerHeld} at the start of the level tick, not here: players tick after the level,
     * while the next physics step already runs on its own thread, and waiting for it here (to steer from the current
     * pose) made every step synchronous for as long as anything was held, which dropped the server's TPS.
     */
    @Override public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {}

    /**
     * Start of a level tick, right after the last physics step was picked up: every player holding the wand steers
     * their body from its current pose, and the server never waits for the physics thread.
     */
    public static void steerHeld(ServerLevel server) {
        for (Player player : server.players())
            if (player.isUsingItem() && player.getUseItem().getItem() instanceof PhysicsWandItem) steer(server, player);
    }

    private static void steer(ServerLevel server, Player player) {
        if (!player.isAlive() || !player.getAbilities().instabuild) return;
        var data = player.getPersistentData();
        if (data.getBoolean(WAIT)) return;
        Entity entity = data.hasUUID(TARGET) ? server.getEntity(data.getUUID(TARGET)) : null;
        PhysicsBody body = entity == null ? null : PhysicalistBodies.find(entity);
        if (body == null && !grab(player)) return;
        entity = server.getEntity(data.getUUID(TARGET));
        body = entity == null ? null : PhysicalistBodies.find(entity);
        if (body == null) return;
        Vec3 goal = player.getEyePosition().add(player.getLookAngle().scale(data.getDouble(DISTANCE)));
        int part = data.getInt(PART);
        CompoundTag grip = data.getCompound(GRIP);
        Vec3 local = new Vec3(grip.getDouble("X"), grip.getDouble("Y"), grip.getDouble("Z"));
        Quaternionf aim = facing(player).mul(relative(grip));
        if (entity instanceof PlayerRagdollEntity doll && part >= 0) {
            doll.grabLimb(part, local, goal, aim);
            return;
        }
        if (entity instanceof PhysicalistDragTarget target) {
            target.physicalistDrag(player, goal);
            return;
        }
        if (entity instanceof PhysicalistRotatable) {
            entity.setDeltaMovement(GrabController.velocity(goal.subtract(entity.position()),
                    entity.getDeltaMovement(), body.profile().gravity()));
            Vec3 angular = body.angularVelocity().scale(.45);
            body.setAngularVelocity(angular.lengthSqr() < 1e-6 ? Vec3.ZERO : angular);
            entity.hasImpulse = true;
            return;
        }
        // Turned toward the held orientation, and moved so the grabbed point (not the centre) reaches the goal.
        Quaternionf now = body.nativeOrientation();
        Quaternionf error = new Quaternionf(aim).mul(new Quaternionf(now).conjugate());
        if (error.w < 0) error.set(-error.x, -error.y, -error.z, -error.w);
        double angle = 2 * Math.acos(Math.min(1, error.w)), sin = Math.sqrt(Math.max(1e-12, 1 - error.w * error.w));
        Vec3 spin = angle < 1e-4 ? Vec3.ZERO : new Vec3(error.x / sin, error.y / sin, error.z / sin).scale(Math.min(.8, angle * .5));
        body.setAngularVelocity(spin);
        Vec3 center = goal.subtract(turn(aim, local));
        entity.setDeltaMovement(GrabController.velocity(center.subtract(body.centerOfMass()),
                entity.getDeltaMovement(), body.profile().gravity()));
        entity.hasImpulse = true;
    }

    /** Picks up the body under the crosshair at the aimed point. */
    private static boolean grab(Player player) {
        var hit = PhysicalistTargeting.aim(player, 64);
        if (hit == null) return false;
        Entity entity = hit.entity();
        PhysicsBody body = PhysicalistBodies.find(entity);
        if (body == null) return false;
        var data = player.getPersistentData();
        boolean was = PhysicalistFreeze.frozen(entity);
        PhysicalistFreeze.unfreeze(entity);
        Quaternionf frame;
        Vec3 origin;
        int part;
        if (entity instanceof PlayerRagdollEntity doll) {
            part = Math.clamp(hit.part(), 0, 5);
            var limb = doll.limbs().get(part);
            frame = new Quaternionf(limb.orientation);
            origin = limb.position;
        } else if (entity instanceof PhysicalistDragTarget) {
            part = -2;
            frame = new Quaternionf();
            origin = hit.point();
        } else {
            part = -1;
            frame = body.nativeOrientation();
            origin = body.centerOfMass();
        }
        Vec3 local = turn(new Quaternionf(frame).conjugate(), hit.point().subtract(origin));
        Quaternionf relative = facing(player).conjugate().mul(frame);
        var grip = new CompoundTag();
        grip.putDouble("X", local.x); grip.putDouble("Y", local.y); grip.putDouble("Z", local.z);
        grip.putFloat("QX", relative.x); grip.putFloat("QY", relative.y);
        grip.putFloat("QZ", relative.z); grip.putFloat("QW", relative.w);
        data.putUUID(TARGET, entity.getUUID());
        data.putInt(PART, part);
        data.put(GRIP, grip);
        data.putDouble(DISTANCE, Math.clamp(player.getEyePosition().distanceTo(hit.point()), 1, 64));
        player.displayClientMessage(Component.translatable(was ? "message.physicalist_library.wand_unfrozen"
                : "message.physicalist_library.wand_grabbed", entity.getId()), true);
        return true;
    }

    static void adjustDistance(Player player, float direction) {
        if (!(player.level() instanceof ServerLevel server)) return;
        var data = player.getPersistentData();
        if (!data.hasUUID(TARGET)) return;
        Entity entity = server.getEntity(data.getUUID(TARGET));
        if (entity == null || PhysicalistBodies.find(entity) == null) return;
        double distance = data.getDouble(DISTANCE);
        data.putDouble(DISTANCE, Math.clamp(distance + Math.signum(direction)
                * Math.max(.25, distance * .1), 1, 256));
    }

    /**
     * Turns the held body: mouse left/right spins it about the vertical, up/down tips it toward or away from you. The
     * turn is kept relative to where you face, so it stays as you set it while you walk round with it.
     */
    static void rotate(Player player, float yaw, float pitch) {
        if (!(player.level() instanceof ServerLevel server)) return;
        var data = player.getPersistentData();
        if (!data.hasUUID(TARGET)) return;
        // Only the grip changes here; the body is steered at the next level tick. No need to wait for the physics thread.
        Entity entity = server.getEntity(data.getUUID(TARGET));
        if (entity == null) return;
        PhysicsBody body = PhysicalistBodies.find(entity);
        if (body == null) return;
        yaw = Math.clamp(yaw, -12, 12);
        pitch = Math.clamp(pitch, -12, 12);
        if (entity instanceof PhysicalistRotatable rotatable) {
            rotatable.physicalistRotate(player, yaw, pitch);
            return;
        }
        CompoundTag grip = data.getCompound(GRIP);
        Quaternionf relative = new Quaternionf().rotationY((float) Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(-pitch)).mul(relative(grip));
        grip.putFloat("QX", relative.x); grip.putFloat("QY", relative.y);
        grip.putFloat("QZ", relative.z); grip.putFloat("QW", relative.w);
        data.put(GRIP, grip);
    }

    /** Left click while holding: freezes the held body (or the held ragdoll limb) where it is and lets go. */
    static void freeze(Player player) {
        if (!(player.level() instanceof ServerLevel server)) return;
        var data = player.getPersistentData();
        if (!data.hasUUID(TARGET)) return;
        dev.physicalist.nativecore.NativeWorlds.settle(server);
        Entity entity = server.getEntity(data.getUUID(TARGET));
        if (entity == null || PhysicalistBodies.find(entity) == null) return;
        int part = data.getInt(PART);
        if (part == -2) {
            player.displayClientMessage(Component.translatable("message.physicalist_library.wand_cannot_freeze"), true);
            return;
        }
        PhysicalistFreeze.freeze(entity, part);
        release(player);
        data.putBoolean(WAIT, true);
        player.displayClientMessage(Component.translatable("message.physicalist_library.wand_frozen", entity.getId()), true);
        server.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_PLACE, net.minecraft.sounds.SoundSource.PLAYERS, .8F, 1.6F);
    }

    private static void release(LivingEntity user) {
        var data = user.getPersistentData();
        data.remove(TARGET);
        data.remove(DISTANCE);
        data.remove(PART);
        data.remove(GRIP);
    }

    @Override public void releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        release(user);
        user.getPersistentData().remove(WAIT);
    }
}
