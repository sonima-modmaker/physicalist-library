package dev.physicalist;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Small real-server checks for registration, block shape capture and scale. */
@GameTestHolder(PhysicalistLibrary.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhysicalistGameTests {
    @GameTest(template = "empty")
    public static void toolsSelectActualCollisionPart(GameTestHelper helper) {
        var wing = new CompoundCollision.Box(new Vec3(3, 3, 3),
                new Vec3[]{new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)},
                new double[]{2, .025, .5});
        helper.assertTrue(Double.isFinite(PhysicalistTargeting.rayFraction(wing,
                        new Vec3(3, 4, 3), new Vec3(3, 2, 3))),
                "Wand ray missed a thin wing");
        helper.assertTrue(!Double.isFinite(PhysicalistTargeting.rayFraction(wing,
                        new Vec3(3, 4, 4), new Vec3(3, 2, 4))),
                "Wand selected empty space beside a thin wing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void physicalBlockShapeAndScale(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(pos, Blocks.OAK_STAIRS.defaultBlockState(), 3);
        var state = level.getBlockState(pos);
        PhysicalBlockEntity entity = PhysicalBlockEntity.fromBlock(level, pos, state);
        helper.assertTrue(entity != null, "Stairs must have a usable collision shape");
        int parts = entity.physicalistBody().collisionBoxes().size();
        helper.assertTrue(parts > 1, "Stairs collision was flattened to a full cube");
        double width = entity.physicalistBody().collisionBoxes().get(0).half()[0];
        entity.setPhysicalistScale(2);
        helper.assertTrue(entity.physicalistBody().collisionBoxes().get(0).half()[0] == width * 2,
                "Visual scale did not scale collision boxes");
        CompoundTag saved = entity.saveWithoutId(new CompoundTag());
        PhysicalBlockEntity restored = PhysicalistContent.PHYSICAL_BLOCK.get().create(level);
        helper.assertTrue(restored != null, "Could not instantiate physical block for load check");
        restored.load(saved);
        helper.assertTrue(restored.physicalistBody().collisionBoxes().size() == parts
                        && restored.physicalistScale() == 2,
                "Saved physical block lost its voxel shape or scale");
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(level.addFreshEntity(entity), "Physical block did not spawn");
        helper.assertTrue(level.getServer().getCommands().getDispatcher().getRoot().getChild("physicalist") != null,
                "Physicalist command was not registered");
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(PhysicalistBodies.find(entity) != null, "Simulation body was not discoverable");
            entity.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void clearFlightAvoidsSubsteps(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 20, 1));
        level.setBlock(source, Blocks.STONE.defaultBlockState(), 3);
        PhysicalBlockEntity entity = PhysicalBlockEntity.fromBlock(level, source, level.getBlockState(source));
        helper.assertTrue(entity != null, "Could not make physical test block");
        level.setBlock(source, Blocks.AIR.defaultBlockState(), 3);
        PhysicsBody delegate = entity.physicalistBody();
        int[] rotations = {0};
        PhysicsBody counted = new PhysicsBody() {
            @Override public Entity entity() { return entity; }
            @Override public List<CompoundCollision.Box> collisionBoxes() { return delegate.collisionBoxes(); }
            @Override public Vec3 forward() { return delegate.forward(); }
            @Override public Vec3 up() { return delegate.up(); }
            @Override public Vec3 angularVelocity() { return delegate.angularVelocity(); }
            @Override public void setAngularVelocity(Vec3 value) { delegate.setAngularVelocity(value); }
            @Override public void rotate(Vec3 value, double fraction) {
                rotations[0]++;
                delegate.rotate(value, fraction);
            }
        };
        double x = entity.getX();
        level.getChunkAt(BlockPos.containing(entity.position().add(4, 0, 0)));
        entity.setDeltaMovement(new Vec3(4, 0, 0));
        PhysicalistSimulation.step(counted);
        helper.assertTrue(rotations[0] == 1,
                "Clear flight still ran " + rotations[0] + " collision substeps");
        helper.assertTrue(entity.getX() > x + 3.8, "Clear-flight shortcut lost forward momentum");
        entity.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void fastBodyStillHitsThinWall(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 20, 1));
        BlockPos wall = helper.absolutePos(new BlockPos(3, 20, 1));
        level.setBlock(source, Blocks.STONE.defaultBlockState(), 3);
        PhysicalBlockEntity entity = PhysicalBlockEntity.fromBlock(level, source, level.getBlockState(source));
        helper.assertTrue(entity != null, "Could not make physical test block");
        level.setBlock(source, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(wall, Blocks.IRON_BARS.defaultBlockState(), 3);
        level.getChunkAt(BlockPos.containing(entity.position().add(4, 0, 0)));
        entity.setDeltaMovement(new Vec3(4, 0, 0));
        PhysicalistSimulation.step(entity.physicalistBody());
        helper.assertTrue(entity.getX() < wall.getX() + .5,
                "Fast physical body passed through a thin block collision");
        entity.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void multiBlockAssemblyKeepsPartsAndInertia(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 20, 1));
        BlockPos second = first.offset(0, 0, 1);
        BlockPos wall = first.offset(2, 0, 1);
        level.setBlock(first, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(second, Blocks.OAK_STAIRS.defaultBlockState(), 3);
        var entity = PhysicalBlockEntity.fromBlocks(level, List.of(first, second));
        helper.assertTrue(entity != null && entity.parts().size() == 2,
                "Assembler did not create one body from two different blocks");
        int collisionParts = entity.physicalistBody().collisionBoxes().size();
        helper.assertTrue(collisionParts > 2, "Assembly lost the stair's detailed collision");
        CompoundTag saved = entity.saveWithoutId(new CompoundTag());
        var restored = PhysicalistContent.PHYSICAL_BLOCK.get().create(level);
        helper.assertTrue(restored != null, "Cannot instantiate saved assembly");
        restored.load(saved);
        helper.assertTrue(restored.parts().size() == 2
                        && restored.physicalistBody().collisionBoxes().size() == collisionParts,
                "Assembled blocks or collision boxes were lost after save/load");
        level.setBlock(first, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(second, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(wall, Blocks.IRON_BARS.defaultBlockState(), 3);
        level.getChunkAt(wall);
        entity.setDeltaMovement(new Vec3(4, 0, 0));
        PhysicalistSimulation.step(entity.physicalistBody());
        helper.assertTrue(entity.getX() < wall.getX() + .5,
                "Physical assembly passed through an obstacle");
        helper.assertTrue(entity.physicalistBody().angularVelocity().lengthSqr() > 1e-8,
                "Off-center impact gave the assembly no rotation");
        entity.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void assemblerConvertsSelectedBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos last = first.offset(1, 0, 0);
        level.setBlock(first, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(last, Blocks.OAK_STAIRS.defaultBlockState(), 3);
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(PhysicalistAssemblerItem.assemble(level, player, first, last)
                        == net.minecraft.world.InteractionResult.SUCCESS,
                "Assembler did not convert the selected blocks");
        helper.assertTrue(level.getBlockState(first).isAir() && level.getBlockState(last).isAir(),
                "Assembler left the source blocks in the world");
        helper.assertTrue(level.getServer().getCommands().getDispatcher().getRoot()
                        .getChild("physicalist").getChild("assemble") != null,
                "Assembly command is not registered");
        var assembled = level.getEntitiesOfClass(PhysicalBlockEntity.class,
                new net.minecraft.world.phys.AABB(first).inflate(4));
        helper.assertTrue(assembled.size() == 1 && assembled.get(0).parts().size() == 2,
                "Assembler spawned separate blocks instead of one compound entity");
        assembled.get(0).discard();
        helper.succeed();
    }
    private PhysicalistGameTests() {}
}
