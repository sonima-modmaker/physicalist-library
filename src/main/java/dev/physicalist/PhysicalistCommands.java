package dev.physicalist;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator tools for selecting, scaling, removing and creating physics bodies. */
public final class PhysicalistCommands {
    private static final String SELECTED = "PhysicalistSelectedBody";

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("physicalist")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("select")
                        .then(Commands.literal("look").executes(ctx -> selectLook(ctx.getSource())))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> select(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("delete").executes(ctx -> delete(ctx.getSource())))
                .then(Commands.literal("scale")
                        .then(Commands.argument("factor", DoubleArgumentType.doubleArg(.1, 16))
                                .executes(ctx -> scale(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "factor")))))
                .then(Commands.literal("block")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> convertBlock(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(Commands.literal("assemble")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(ctx -> assemble(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "to"))))))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("block", StringArgumentType.word())
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> spawnBlock(ctx, BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))));
    }

    private static int list(CommandSourceStack source) {
        int count = 0;
        for (Entity entity : source.getLevel().getAllEntities()) {
            if (PhysicalistBodies.find(entity) == null) continue;
            count++;
            int id = entity.getId();
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            source.sendSuccess(() -> Component.literal("#" + id + " " + type + " " + entity.blockPosition().toShortString()), false);
        }
        int total = count;
        source.sendSuccess(() -> Component.literal("Loaded physical entities: " + total), false);
        return count;
    }

    private static ServerPlayer player(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer p ? p : null;
    }

    private static int selectLook(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) return failure(source, "Selection requires a player");
        Entity entity = PhysicalistTargeting.aimedBody(player, 64);
        return entity == null ? failure(source, "No physical entity under crosshair") : select(source, entity.getId());
    }

    private static int select(CommandSourceStack source, int id) {
        ServerPlayer player = player(source);
        if (player == null) return failure(source, "Selection requires a player");
        Entity entity = source.getLevel().getEntity(id);
        if (entity == null || PhysicalistBodies.find(entity) == null)
            return failure(source, "That ID is not a loaded Physicalist body");
        player.getPersistentData().putUUID(SELECTED, entity.getUUID());
        source.sendSuccess(() -> Component.literal("Selected physical entity #" + id), true);
        return 1;
    }

    private static Entity selected(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null || !player.getPersistentData().hasUUID(SELECTED)) return null;
        Entity entity = source.getLevel().getEntity(player.getPersistentData().getUUID(SELECTED));
        return entity != null && PhysicalistBodies.find(entity) != null ? entity : null;
    }

    private static int info(CommandSourceStack source) {
        Entity entity = selected(source);
        if (entity == null) return failure(source, "Select a loaded physical entity first");
        int parts = PhysicalistBodies.find(entity).collisionBoxes().size();
        String scale = entity instanceof PhysicalistScalable scalable
                ? String.valueOf(scalable.physicalistScale()) : "unsupported";
        source.sendSuccess(() -> Component.literal("#" + entity.getId() + " parts=" + parts
                + " scale=" + scale + " velocity=" + entity.getDeltaMovement().length()), false);
        return 1;
    }

    private static int delete(CommandSourceStack source) {
        Entity entity = selected(source);
        if (entity == null) return failure(source, "Select a loaded physical entity first");
        int id = entity.getId();
        entity.discard();
        player(source).getPersistentData().remove(SELECTED);
        source.sendSuccess(() -> Component.literal("Deleted physical entity #" + id), true);
        return 1;
    }

    private static int scale(CommandSourceStack source, double factor) {
        Entity entity = selected(source);
        if (entity == null) return failure(source, "Select a loaded physical entity first");
        if (!(entity instanceof PhysicalistScalable scalable))
            return failure(source, "This mod's physical entity does not expose scalable geometry");
        scalable.setPhysicalistScale(factor);
        source.sendSuccess(() -> Component.literal("Physical entity #" + entity.getId() + " scale=" + factor), true);
        return 1;
    }

    private static int convertBlock(CommandSourceStack source, BlockPos pos) {
        ServerLevel level = source.getLevel();
        var state = level.getBlockState(pos);
        PhysicalBlockEntity entity = PhysicalBlockEntity.fromBlock(level, pos, state);
        if (entity == null) return failure(source, "Block needs a nonempty collision shape and no block entity");
        if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) return failure(source, "Cannot remove block");
        if (!level.addFreshEntity(entity)) {
            level.setBlock(pos, state, 3);
            return failure(source, "Cannot spawn physical block");
        }
        source.sendSuccess(() -> Component.literal("Converted block to physical entity #" + entity.getId()), true);
        return 1;
    }

    private static int spawnBlock(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        CommandSourceStack source = ctx.getSource();
        ResourceLocation key = ResourceLocation.tryParse(StringArgumentType.getString(ctx, "block"));
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return failure(source, "Unknown block");
        var block = BuiltInRegistries.BLOCK.get(key);
        PhysicalBlockEntity entity = PhysicalBlockEntity.fromBlock(source.getLevel(), pos, block.defaultBlockState());
        if (entity == null || !source.getLevel().addFreshEntity(entity))
            return failure(source, "Block has no supported collision shape");
        source.sendSuccess(() -> Component.literal("Spawned physical " + key + " #" + entity.getId()), true);
        return 1;
    }

    private static int assemble(CommandSourceStack source, BlockPos first, BlockPos last) {
        ServerLevel level = source.getLevel();
        int minX = Math.min(first.getX(), last.getX()), maxX = Math.max(first.getX(), last.getX());
        int minY = Math.min(first.getY(), last.getY()), maxY = Math.max(first.getY(), last.getY());
        int minZ = Math.min(first.getZ(), last.getZ()), maxZ = Math.max(first.getZ(), last.getZ());
        if (maxX - minX >= PhysicalBlockEntity.MAX_SPAN || maxY - minY >= PhysicalBlockEntity.MAX_SPAN
                || maxZ - minZ >= PhysicalBlockEntity.MAX_SPAN || !level.hasChunksAt(minX, minZ, maxX, maxZ))
            return failure(source, "Selection too large or crosses unloaded chunks");
        List<BlockPos> positions = new ArrayList<>();
        List<BlockState> original = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(first, last)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            positions.add(pos.immutable());
            original.add(state);
            if (positions.size() > PhysicalBlockEntity.MAX_BLOCKS)
                return failure(source, "Assembly exceeds the block limit");
        }
        PhysicalBlockEntity entity = PhysicalBlockEntity.fromBlocks(level, positions);
        if (entity == null) return failure(source, "Unsupported assembly or collision geometry");
        for (int i = 0; i < positions.size(); i++) {
            if (!level.setBlock(positions.get(i), Blocks.AIR.defaultBlockState(), 3)) {
                for (int j = 0; j < i; j++) level.setBlock(positions.get(j), original.get(j), 3);
                return failure(source, "Could not remove selected blocks");
            }
        }
        if (!level.addFreshEntity(entity)) {
            for (int i = 0; i < positions.size(); i++) level.setBlock(positions.get(i), original.get(i), 3);
            return failure(source, "Could not spawn physical assembly");
        }
        source.sendSuccess(() -> Component.literal("Assembled " + positions.size()
                + " blocks into physical entity #" + entity.getId()), true);
        return 1;
    }

    private static int failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private PhysicalistCommands() {}
}
