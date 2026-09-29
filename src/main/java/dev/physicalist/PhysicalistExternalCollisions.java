package dev.physicalist;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Optional moving-world collision source; the library itself does not depend on Sable. */
public final class PhysicalistExternalCollisions {
    public record Collider(CompoundCollision.Box box, Vec3 velocity,
                           BiConsumer<Vec3, Vec3> reaction) {}

    @FunctionalInterface public interface Provider {
        List<Collider> gather(ServerLevel level, Entity body, AABB sweptBounds);
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    public static void register(Provider provider) { PROVIDERS.add(provider); }

    public static boolean available() { return !PROVIDERS.isEmpty(); }

    public static List<Collider> gather(ServerLevel level, Entity body, AABB bounds) {
        if (PROVIDERS.isEmpty()) return List.of();
        var result = new java.util.ArrayList<Collider>();
        for (Provider provider : PROVIDERS) result.addAll(provider.gather(level, body, bounds));
        return result;
    }

    private PhysicalistExternalCollisions() {}
}
