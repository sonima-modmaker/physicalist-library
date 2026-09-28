package dev.physicalist;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Select two corners, then assemble their blocks into one compound physical entity. */
public final class PhysicalistAssemblerItem extends Item {
    private static final String SELECTION = "PhysicalistAssemblyCorner";
    private static final String DIMENSION = "PhysicalistAssemblyDimension";

    public PhysicalistAssemblerItem(Properties properties) { super(properties); }

    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.getAbilities().instabuild) return InteractionResult.FAIL;
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        BlockPos clicked = context.getClickedPos();
        var data = player.getPersistentData();
        String dimension = level.dimension().location().toString();
        if (player.isShiftKeyDown()) {
            data.remove(SELECTION);
            data.remove(DIMENSION);
            player.displayClientMessage(Component.literal("Assembly selection cleared"), true);
            return InteractionResult.SUCCESS;
        }
        if (!data.contains(SELECTION) || !dimension.equals(data.getString(DIMENSION))) {
            data.putLong(SELECTION, clicked.asLong());
            data.putString(DIMENSION, dimension);
            player.displayClientMessage(Component.literal("First corner selected; right click the opposite corner"), true);
            return InteractionResult.SUCCESS;
        }
        BlockPos first = BlockPos.of(data.getLong(SELECTION));
        data.remove(SELECTION);
        data.remove(DIMENSION);
        return assemble(level, player, first, clicked);
    }

    static InteractionResult assemble(ServerLevel level, Player player, BlockPos first, BlockPos last) {
        int minX = Math.min(first.getX(), last.getX()), maxX = Math.max(first.getX(), last.getX());
        int minY = Math.min(first.getY(), last.getY()), maxY = Math.max(first.getY(), last.getY());
        int minZ = Math.min(first.getZ(), last.getZ()), maxZ = Math.max(first.getZ(), last.getZ());
        if (maxX - minX >= PhysicalBlockEntity.MAX_SPAN || maxY - minY >= PhysicalBlockEntity.MAX_SPAN
                || maxZ - minZ >= PhysicalBlockEntity.MAX_SPAN || !level.hasChunksAt(minX, minZ, maxX, maxZ))
            return fail(player, "Selection is too large or crosses unloaded chunks");
        List<BlockPos> positions = new ArrayList<>();
        List<BlockState> original = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(first, last)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos,
                    net.minecraft.core.Direction.UP, player.getMainHandItem()))
                return fail(player, "You cannot modify part of this selection");
            positions.add(pos.immutable());
            original.add(state);
            if (positions.size() > PhysicalBlockEntity.MAX_BLOCKS)
                return fail(player, "Assembly exceeds the block limit");
        }
        PhysicalBlockEntity entity = PhysicalBlockEntity.fromBlocks(level, positions);
        if (entity == null) return fail(player, "Assembly contains unsupported blocks or too many collision parts");
        for (int i = 0; i < positions.size(); i++) {
            if (!level.setBlock(positions.get(i), Blocks.AIR.defaultBlockState(), 3)) {
                restore(level, positions, original, i);
                return fail(player, "Could not remove selected blocks");
            }
        }
        if (!level.addFreshEntity(entity)) {
            restore(level, positions, original, positions.size());
            return fail(player, "Could not spawn physical assembly");
        }
        player.displayClientMessage(Component.literal("Assembled " + positions.size()
                + " blocks into physical entity #" + entity.getId()), true);
        return InteractionResult.SUCCESS;
    }

    private static void restore(ServerLevel level, List<BlockPos> positions, List<BlockState> states, int count) {
        for (int i = 0; i < count; i++) level.setBlock(positions.get(i), states.get(i), 3);
    }

    private static InteractionResult fail(Player player, String message) {
        player.displayClientMessage(Component.literal(message), true);
        return InteractionResult.FAIL;
    }
}
