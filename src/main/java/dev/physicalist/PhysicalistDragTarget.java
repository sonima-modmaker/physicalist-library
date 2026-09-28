package dev.physicalist;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Lets an external flight solver apply the wand's grab goal using its own impulse rules. */
public interface PhysicalistDragTarget {
    void physicalistDrag(Player player, Vec3 goal);
}
