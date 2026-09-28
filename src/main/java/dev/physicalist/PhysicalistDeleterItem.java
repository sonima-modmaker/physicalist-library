package dev.physicalist;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Deletes the physical entity under the crosshair without dropping its block. */
public final class PhysicalistDeleterItem extends Item {
    public PhysicalistDeleterItem(Properties properties) { super(properties); }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.getAbilities().instabuild) return InteractionResultHolder.fail(stack);
        if (level instanceof ServerLevel) {
            var entity = PhysicalistTargeting.aimedBody(player, 64);
            if (entity != null) {
                int id = entity.getId();
                entity.discard();
                player.displayClientMessage(Component.literal("Deleted physical entity #" + id), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
