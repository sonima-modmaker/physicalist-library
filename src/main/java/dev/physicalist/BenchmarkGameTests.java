package dev.physicalist;

import dev.physicalist.nativecore.NativeWorlds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed heavy scene for measuring server tick cost: a pile of 125 falling blocks, four chains holding bodies
 * and three ragdolls, stepped by hand for 400 ticks. Writes timings and a bit-exact state checksum to
 * physicalist-benchmark.txt, so an optimisation can be shown not to change the simulation.
 * Runs only with -Dphysicalist.benchmark=true (gradle -Pphysicalist_bench).
 */
@GameTestHolder(PhysicalistLibrary.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BenchmarkGameTests {
    private static final int WARMUP = 100, MEASURED = 300;

    @GameTest(template = "empty", timeoutTicks = 100, batch = "physicalistBenchmark")
    public static void heavySceneTickCost(GameTestHelper h) {
        if (!Boolean.getBoolean("physicalist.benchmark") || !NativeWorlds.enabled()) { h.succeed(); return; }
        var level = h.getLevel();
        // The native scene is per dimension: bodies left by earlier tests stay simulated for 100 frames
        // and made timings and checksums differ between runs. Clear them first.
        List<Entity> leftovers = new ArrayList<>();
        for (Entity e : level.getAllEntities())
            if (e instanceof PhysicalBlockEntity || e instanceof PhysicalChainEntity || e instanceof PlayerRagdollEntity || PhysicalistBodies.find(e) != null) leftovers.add(e);
        leftovers.forEach(Entity::discard);
        for (int i = 0; i < 110; i++) NativeWorlds.flush(level);
        BlockPos base = h.absolutePos(new BlockPos(1, 100, 1));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.getChunk((base.getX() >> 4) + x, (base.getZ() >> 4) + z);
        for (int x = -6; x <= 10; x++) for (int z = -6; z <= 10; z++) level.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);

        List<Entity> entities = new ArrayList<>();
        List<PhysicalBlockEntity> bodies = new ArrayList<>();
        String parts = System.getProperty("physicalist.benchmark.parts", "pile,chains,dolls");
        for (int layer = 0; layer < (parts.contains("pile") ? 5 : 0); layer++)
            for (int x = 0; x < 5; x++)
                for (int z = 0; z < 5; z++) {
                    var body = PhysicalBlockEntity.fromBlock(level, base.offset(x, 2 + layer * 2, z), Blocks.STONE.defaultBlockState());
                    level.addFreshEntity(body);
                    bodies.add(body);
                    entities.add(body);
                }
        for (int i = 0; i < (parts.contains("chains") ? 4 : 0); i++) {
            BlockPos at = base.offset(i < 2 ? -4 : 8, 8, i % 2 == 0 ? -4 : 8);
            var body = PhysicalBlockEntity.fromBlock(level, at, Blocks.STONE.defaultBlockState());
            level.addFreshEntity(body);
            bodies.add(body);
            entities.add(body);
            var chain = PhysicalistContent.PHYSICAL_CHAIN.get().create(level);
            chain.initialize(ChainEndpoint.world(body.position().add(1.5, 5, 0)), ChainEndpoint.entity(body, body.position().add(0, .5, 0)));
            level.addFreshEntity(chain);
            entities.add(chain);
        }
        List<PlayerRagdollEntity> dolls = new ArrayList<>();
        for (int i = 0; i < (parts.contains("dolls") ? 3 : 0); i++) {
            var doll = PhysicalistContent.PLAYER_RAGDOLL.get().create(level);
            doll.initialize(h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE), base.offset(1 + i, 14, 2).getCenter());
            level.addFreshEntity(doll);
            dolls.add(doll);
            entities.add(doll);
        }

        long entityNanos = 0, flushNanos = 0;
        var progress = new StringBuilder();
        try {
            for (int tick = 0; tick < WARMUP + MEASURED; tick++) {
                long a = System.nanoTime();
                for (Entity e : entities) if (!e.isRemoved()) e.tick();
                long b = System.nanoTime();
                NativeWorlds.flush(level);
                long c = System.nanoTime();
                if (tick >= WARMUP) { entityNanos += b - a; flushNanos += c - b; }
                if (tick % 50 == 49) progress.append("tick ").append(tick + 1).append(String.format(java.util.Locale.ROOT, " %.2f ms: ", (c - b) / 1e6)).append(String.join(" | ", NativeWorlds.report())).append('\n');
            }
            long hash = 1469598103934665603L;
            for (var body : bodies) hash = mix(hash, body.position(), body.getDeltaMovement());
            for (var doll : dolls) for (var limb : doll.limbs()) hash = mix(hash, limb.position, limb.velocity);
            for (Entity e : entities) if (e instanceof PhysicalChainEntity chain) for (var link : chain.links()) hash = mix(hash, link.position, link.velocity);
            String report = String.format(java.util.Locale.ROOT,
                    "parts=" + parts + " bodies=%d ticks=%d%nentity ticks: %.3f ms/tick%nphysics flush: %.3f ms/tick%ntotal: %.3f ms/tick%nchecksum=%016x%n%s",
                    bodies.size(), MEASURED, entityNanos / 1e6 / MEASURED, flushNanos / 1e6 / MEASURED, (entityNanos + flushNanos) / 1e6 / MEASURED, hash, progress);
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("physicalist-benchmark.txt"), report); } catch (java.io.IOException ignored) {}
            org.slf4j.LoggerFactory.getLogger("Physicalist/Benchmark").info("Physicalist benchmark\n{}", report);
            h.succeed();
        } finally {
            entities.forEach(Entity::discard);
        }
    }

    private static long mix(long hash, Vec3... values) {
        for (Vec3 v : values)
            for (double d : new double[]{v.x, v.y, v.z}) { hash ^= Double.doubleToLongBits(d); hash *= 1099511628211L; }
        return hash;
    }

    private BenchmarkGameTests() {}
}
