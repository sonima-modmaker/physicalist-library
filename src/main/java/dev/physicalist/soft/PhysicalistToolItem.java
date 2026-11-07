package dev.physicalist.soft;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Physicalist debug tool: right-click into the air opens the soft-body menu (pick a model, spawn it); right-click
 * on a soft body opens its doors, Shift+right-click or a hit removes it, R starts it over.
 */
public final class PhysicalistToolItem extends Item {
    public PhysicalistToolItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // Soft bodies are experimental: off unless switched on in the config.
        if (!dev.physicalist.PhysicalistConfig.softBodies()) {
            if (level.isClientSide) player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.physicalist_library.soft_experimental"), true);
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        if (level.isClientSide) dev.physicalist.client.SoftBodyClient.openMenu();
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
