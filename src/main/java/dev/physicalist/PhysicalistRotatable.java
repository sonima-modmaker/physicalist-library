package dev.physicalist;

import net.minecraft.world.entity.player.Player;

/** Optional collision-aware rotation for bodies controlled by another mod's solver. */
public interface PhysicalistRotatable {
    void physicalistRotate(Player player, float yawDegrees, float pitchDegrees);
}
