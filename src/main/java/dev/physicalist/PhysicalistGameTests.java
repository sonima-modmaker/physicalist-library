package dev.physicalist;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Small real-server checks for registration, block shape capture and scale. */
@GameTestHolder(PhysicalistLibrary.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhysicalistGameTests {
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
    private PhysicalistGameTests() {}
}
