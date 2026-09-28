package dev.physicalist;

/** Opt-in for mods that use Physicalist calculations without the generic simulation. */
public interface PhysicalistBodyProvider {
    PhysicsBody physicalistBody();
}
