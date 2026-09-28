package dev.physicalist;

/** Opt-in for mods that use Physicalist calculations without the generic simulation. */
public interface PhysicalistBodyProvider {
    PhysicsBody physicalistBody();

    /** Integrations with their own F3+B renderer can avoid drawing the same boxes twice. */
    default boolean physicalistRenderDebugCollision() { return true; }
}
