package dev.physicalist;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;

/** Converts one ordinary block into a free physical entity using its voxel collision. */
public final class PhysicalistAssemblerItem extends Item {
    public PhysicalistAssemblerItem(Properties properties) { super(properties); }

    @Override public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || !player.getAbilities().instabuild) return InteractionResult.FAIL;
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        var pos = context.getClickedPos();
        if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand()))
            return InteractionResult.FAIL;
        var state = level.getBlockState(pos);
        PhysicalBlockEntity entity = PhysicalBlockEntity.fromBlock(level, pos, state);
        if (entity == null) {
            player.displayClientMessage(Component.literal("Block has unsupported, empty, or block-entity collision"), true);
            return InteractionResult.FAIL;
        }
        if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) return InteractionResult.FAIL;
        if (!level.addFreshEntity(entity)) {
            level.setBlock(pos, state, 3);
            return InteractionResult.FAIL;
        }
        player.displayClientMessage(Component.literal("Created physical block #" + entity.getId()), true);
        return InteractionResult.SUCCESS;
    }
}
