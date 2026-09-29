package dev.physicalist;

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

/** Hold right click to grab any live body registered with the library. */
public final class PhysicsWandItem extends Item {
    private static final String TARGET = "PhysicalistWandTarget";
    private static final String DISTANCE = "PhysicalistWandDistance";
    public PhysicsWandItem(Properties properties) { super(properties); }

    @Override public int getUseDuration(ItemStack stack, LivingEntity user) { return 72000; }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!player.getAbilities().instabuild) return InteractionResultHolder.fail(player.getItemInHand(hand));
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
        if (!(level instanceof ServerLevel server) || !(user instanceof Player player)
                || !player.getAbilities().instabuild) return;
        var data = player.getPersistentData();
        Entity entity = data.hasUUID(TARGET) ? server.getEntity(data.getUUID(TARGET)) : null;
        PhysicsBody body = entity == null ? null : PhysicalistBodies.find(entity);
        if (body == null) {
            entity = PhysicalistTargeting.aimedBody(player, 64);
            if (entity == null) return;
            body = PhysicalistBodies.find(entity);
            if (body == null) return;
            data.putUUID(TARGET, entity.getUUID());
            data.putDouble(DISTANCE, Math.clamp(player.getEyePosition().distanceTo(entity.position()), 2, 64));
            player.displayClientMessage(Component.literal("Grabbed physical entity #" + entity.getId()), true);
        }
        Vec3 goal = player.getEyePosition().add(player.getLookAngle().scale(data.getDouble(DISTANCE)));
        if (entity instanceof PhysicalistDragTarget target) {
            target.physicalistDrag(player, goal);
            return;
        }
        Vec3 wanted = goal.subtract(entity.position()).scale(.22)
                .subtract(entity.getDeltaMovement().scale(.58));
        if (wanted.length() > 2) wanted = wanted.normalize().scale(2);
        // Cancel the solver's gravity while held so the body can actually settle at the cursor.
        entity.setDeltaMovement(entity.getDeltaMovement().add(wanted)
                .add(0, body.profile().gravity(), 0));
        Vec3 angular = body.angularVelocity().scale(.45);
        body.setAngularVelocity(angular.lengthSqr() < 1e-6 ? Vec3.ZERO : angular);
        entity.hasImpulse = true;
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

    static void rotate(Player player, float yaw, float pitch) {
        if (!(player.level() instanceof ServerLevel server)) return;
        var data = player.getPersistentData();
        if (!data.hasUUID(TARGET)) return;
        Entity entity = server.getEntity(data.getUUID(TARGET));
        if (entity == null) return;
        PhysicsBody body = PhysicalistBodies.find(entity);
        if (body == null) return;
        yaw = Math.clamp(yaw, -12, 12);
        pitch = Math.clamp(pitch, -12, 12);
        if (entity instanceof PhysicalistRotatable rotatable) rotatable.physicalistRotate(player, yaw, pitch);
        else {
            body.rotate(new Vec3(Math.toRadians(pitch), -Math.toRadians(yaw), 0), 1);
            body.setAngularVelocity(Vec3.ZERO);
            entity.hasImpulse = true;
        }
    }

    @Override public void releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        user.getPersistentData().remove(TARGET);
        user.getPersistentData().remove(DISTANCE);
    }
}
