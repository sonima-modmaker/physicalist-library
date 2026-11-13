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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator tools for selecting, scaling, removing and creating physics bodies. */
public final class PhysicalistCommands {
    private static final String SELECTED = "PhysicalistSelectedBody";

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("physicalist")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("ragdoll")
                        .executes(ctx -> ragdoll(ctx.getSource(),ctx.getSource().getPlayerOrException()))
                        .then(Commands.literal("mob")
                                .then(Commands.argument("type", net.minecraft.commands.arguments.ResourceArgument.resource(event.getBuildContext(), net.minecraft.core.registries.Registries.ENTITY_TYPE))
                                        .suggests(net.minecraft.commands.synchronization.SuggestionProviders.SUMMONABLE_ENTITIES)
                                        .executes(ctx -> ragdollMob(ctx.getSource(),net.minecraft.commands.arguments.ResourceArgument.getSummonableEntityType(ctx,"type").value()))))
                        .then(Commands.literal("of")
                                .then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.entities())
                                        .executes(ctx -> ragdollOf(ctx.getSource(),net.minecraft.commands.arguments.EntityArgument.getEntities(ctx,"targets")))))
                        .then(Commands.literal("ondeath")
                                .executes(ctx -> {ctx.getSource().sendSuccess(()->Component.literal("Ragdolls on death: "+(PhysicalistRagdolls.onDeathEnabled()?"on":"off")),false);return 1;})
                                .then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                        .executes(ctx -> {boolean on=com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx,"on");PhysicalistRagdolls.setOnDeath(on);ctx.getSource().sendSuccess(()->Component.literal("Ragdolls on death "+(on?"on":"off")),true);return 1;})))
                        .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes(ctx -> ragdoll(ctx.getSource(),net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx,"player")))))
                .then(Commands.literal("engine").executes(ctx -> {for(String line:dev.physicalist.nativecore.NativeWorlds.report())ctx.getSource().sendSuccess(()->Component.literal(line),false);return 1;}))
                .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource(),"show"))
                        .then(Commands.literal("start").executes(ctx -> stats(ctx.getSource(),"start")))
                        .then(Commands.literal("stop").executes(ctx -> stats(ctx.getSource(),"stop")))
                        .then(Commands.literal("reset").executes(ctx -> stats(ctx.getSource(),"reset"))))
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

    private static int ragdoll(CommandSourceStack source,ServerPlayer player){
        var level=source.getLevel();int count=level.getEntitiesOfClass(PlayerRagdollEntity.class,new net.minecraft.world.phys.AABB(source.getPosition(),source.getPosition()).inflate(128)).size();
        if(count>=32)return failure(source,"At most 32 nearby test ragdolls; delete old dummies first");
        var entity=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);if(entity==null)return 0;
        entity.initialize(player,source.getPosition().add(source.getEntity() instanceof net.minecraft.world.entity.LivingEntity living?living.getLookAngle().multiply(2,0,2):Vec3.ZERO).add(0,1.15,0));
        if(!level.addFreshEntity(entity))return failure(source,"Cannot spawn ragdoll");
        source.sendSuccess(()->Component.literal("Spawned ragdoll of "+player.getGameProfile().getName()+" #"+entity.getId()+" (player is unaffected)"),true);return 1;
    }
    private static boolean crowded(CommandSourceStack source){
        return source.getLevel().getEntitiesOfClass(PlayerRagdollEntity.class,new net.minecraft.world.phys.AABB(source.getPosition(),source.getPosition()).inflate(128)).size()>=32;
    }
    /** Ragdoll of a freshly spawned mob of that type (random variant), two blocks in front of the caller. */
    private static int ragdollMob(CommandSourceStack source,net.minecraft.world.entity.EntityType<?> type){
        if(crowded(source))return failure(source,"At most 32 nearby test ragdolls; delete old dummies first");
        var level=source.getLevel();Entity mob=type.create(level);if(mob==null)return failure(source,"Cannot create "+BuiltInRegistries.ENTITY_TYPE.getKey(type));
        Vec3 at=source.getPosition().add(source.getEntity() instanceof net.minecraft.world.entity.LivingEntity living?living.getLookAngle().multiply(2,0,2):Vec3.ZERO).add(0,.1,0);
        mob.moveTo(at.x,at.y,at.z,source.getRotation().y+180,0);if(mob instanceof net.minecraft.world.entity.Mob m){m.yBodyRot=m.getYRot();m.finalizeSpawn(level,level.getCurrentDifficultyAt(BlockPos.containing(at)),net.minecraft.world.entity.MobSpawnType.COMMAND,null);}
        var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);if(doll==null)return 0;doll.initialize(mob);mob.discard();
        if(!level.addFreshEntity(doll))return failure(source,"Cannot spawn ragdoll");
        source.sendSuccess(()->Component.literal("Spawned ragdoll of "+BuiltInRegistries.ENTITY_TYPE.getKey(type)+" #"+doll.getId()),true);return 1;
    }
    /** Turns mobs into ragdolls where they stand; players get a copy and are left alone. */
    private static int ragdollOf(CommandSourceStack source,java.util.Collection<? extends Entity> targets){
        int made=0;
        for(Entity target:targets){
            if(target instanceof PlayerRagdollEntity||target instanceof PhysicalChainEntity||PhysicalistBodies.find(target)!=null)continue;
            if(crowded(source))return made>0?made:failure(source,"At most 32 nearby test ragdolls; delete old dummies first");
            var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(source.getLevel());if(doll==null)continue;
            doll.initialize(target);
            if(!source.getLevel().addFreshEntity(doll))continue;if(!(target instanceof net.minecraft.world.entity.player.Player))target.discard();made++;
        }
        int total=made;if(total==0)return failure(source,"No entity could be turned into a ragdoll");
        source.sendSuccess(()->Component.literal("Made "+total+" ragdoll"+(total==1?"":"s")),true);return total;
    }
    private static int stats(CommandSourceStack source,String action){
        switch(action){case "start" -> PhysicsMetrics.start();case "stop" -> PhysicsMetrics.stop();case "reset" -> PhysicsMetrics.reset();}
        source.sendSuccess(()->Component.literal(PhysicsMetrics.report()),false);return 1;
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
        source.sendSuccess(() -> Component.literal(dev.physicalist.nativecore.NativeWorlds.bodyReport(entity)), false);
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
