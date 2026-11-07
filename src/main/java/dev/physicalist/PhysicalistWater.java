package dev.physicalist;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Water surface sampling and material densities for native buoyancy. Densities are relative to water (1 = neutral). */
public final class PhysicalistWater {
    /** Rough material density of a block relative to water. */
    public static double density(BlockState s) {
        if (s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.BEDS)) return .3;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_STAIRS)
                || s.is(BlockTags.WOODEN_FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WOODEN_DOORS)
                || s.is(BlockTags.WOODEN_TRAPDOORS) || s.is(BlockTags.ALL_SIGNS)) return .6;
        if (s.is(BlockTags.ICE)) return .92;
        SoundType sound = s.getSoundType();
        if (sound == SoundType.WOOL) return .3;
        if (sound == SoundType.WOOD || sound == SoundType.NETHER_WOOD || sound == SoundType.CHERRY_WOOD
                || sound == SoundType.BAMBOO_WOOD || sound == SoundType.BAMBOO || sound == SoundType.SCAFFOLDING) return .6;
        if (sound == SoundType.METAL || sound == SoundType.ANVIL || sound == SoundType.NETHERITE_BLOCK
                || sound == SoundType.CHAIN || sound == SoundType.COPPER || sound == SoundType.HEAVY_CORE) return 7.8;
        if (sound == SoundType.GLASS) return 2.5;
        return 2.4;
    }

    /** Applies the global settings: buoyancy multiplier and the "float everything" switch. */
    public static double effective(double density) {
        if (PhysicalistConfig.WATER_FLOAT_ALL.get()) density = Math.min(density, PhysicalistConfig.WATER_FLOAT_ALL_DENSITY.get());
        return Math.max(.05, density);
    }

    /**
     * World Y of the water surface over this box, or NaN when its bottom is dry.
     * Samples the centre and bottom corners so a box straddling a shoreline still floats.
     */
    public static double surface(Level level, AABB box) {
        double best = Double.NaN;
        double[][] columns = {{box.getCenter().x, box.getCenter().z}, {box.minX, box.minZ}, {box.maxX, box.minZ},
                {box.minX, box.maxZ}, {box.maxX, box.maxZ}};
        for (double[] c : columns) {
            double h = column(level, c[0], box.minY, box.maxY, c[1]);
            if (!Double.isNaN(h) && (Double.isNaN(best) || h > best)) best = h;
        }
        return best;
    }

    private static double column(Level level, double x, double bottom, double top, double z) {
        var pos = new BlockPos.MutableBlockPos(x, bottom, z);
        if (!level.isLoaded(pos) || !level.getFluidState(pos).is(FluidTags.WATER)) return Double.NaN;
        int limit = (int) Math.floor(top) + 2;
        while (pos.getY() < limit && level.getFluidState(pos.above()).is(FluidTags.WATER)) pos.move(0, 1, 0);
        // A full column continues above the box; its exact surface no longer matters.
        return pos.getY() + level.getFluidState(pos).getHeight(level, pos);
    }

    private PhysicalistWater() {}
}
