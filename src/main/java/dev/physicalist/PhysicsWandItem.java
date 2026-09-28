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
        Vec3 wanted = goal.subtract(entity.position()).scale(.35).subtract(entity.getDeltaMovement().scale(.65));
        if (wanted.length() > 3) wanted = wanted.normalize().scale(3);
        entity.setDeltaMovement(entity.getDeltaMovement().add(wanted));
        body.setAngularVelocity(body.angularVelocity().scale(.6));
        entity.hasImpulse = true;
    }

    @Override public void releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        user.getPersistentData().remove(TARGET);
        user.getPersistentData().remove(DISTANCE);
    }
}
