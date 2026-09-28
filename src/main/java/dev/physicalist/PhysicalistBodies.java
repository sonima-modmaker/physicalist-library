package dev.physicalist;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Finds live bodies stepped through PhysicalistSimulation, including bodies from other mods. */
public final class PhysicalistBodies {
    private record Tracked(PhysicsBody body, long tick) {}
    private static final Map<UUID, Tracked> ACTIVE = new HashMap<>();
    private static long lastPrune = Long.MIN_VALUE;

    static void track(PhysicsBody body) {
        Entity entity = body.entity();
        if (!(entity.level() instanceof ServerLevel level)) return;
        long tick = level.getServer().getTickCount();
        ACTIVE.put(entity.getUUID(), new Tracked(body, tick));
        if (tick < lastPrune || tick - lastPrune >= 200 || lastPrune == Long.MIN_VALUE) {
            ACTIVE.entrySet().removeIf(entry -> entry.getValue().body().entity().isRemoved()
                    || entry.getValue().body().entity().level() != level
                    || tick - entry.getValue().tick() > 3);
            lastPrune = tick;
        }
    }

    public static PhysicsBody find(Entity entity) {
        if (entity.isRemoved()) return null;
        Tracked tracked = ACTIVE.get(entity.getUUID());
        if (tracked != null && tracked.body().entity() == entity
                && !entity.isRemoved() && entity.level() instanceof ServerLevel level
                && level.getServer().getTickCount() - tracked.tick() <= 3)
            return tracked.body();
        if (entity instanceof PhysicalistBodyProvider provider) return provider.physicalistBody();
        return null;
    }

    private PhysicalistBodies() {}
}
