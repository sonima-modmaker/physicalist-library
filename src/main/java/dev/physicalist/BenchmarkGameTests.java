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

    /**
     * A pile of 60 gun-like bodies (a mod's dropped weapons: ~0.9 m long, a dozen small boxes, 3.3 kg) left to settle,
     * then hit by one more thrown into it. Logs per-tick cost on the server thread and on the physics thread.
     * Runs only with -Pphysicalist_bench -Pphysicalist_parts=guns.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "physicalistBenchmarkGuns")
    public static void gunPileKnocked(GameTestHelper h) {
        if (!Boolean.getBoolean("physicalist.benchmark") || !System.getProperty("physicalist.benchmark.parts", "").contains("guns") || !NativeWorlds.enabled()) { h.succeed(); return; }
        var level = h.getLevel();
        BlockPos base = h.absolutePos(new BlockPos(1, 100, 1));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.getChunk((base.getX() >> 4) + x, (base.getZ() >> 4) + z);
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) level.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
        List<Gun> guns = new ArrayList<>();
        var rng = new java.util.Random(7);
        for (int i = 0; i < 60; i++) {
            var g = new Gun(level, base.getCenter().add((i % 5) * 0.35 - 0.7, 1 + (i / 5) * 0.25, ((i / 5) % 3) * 0.3 - 0.3),
                    new org.joml.Quaternionf().rotationY(rng.nextFloat() * 6.28f).rotateZ(1.5708f * rng.nextInt(2)));
            guns.add(g);
        }
        var log = new StringBuilder();
        try {
            for (int tick = 0; tick < 260; tick++) {
                if (tick == 160) {
                    var thrown = new Gun(level, base.getCenter().add(-6, 1.6, 0), new org.joml.Quaternionf());
                    thrown.marker.setDeltaMovement(0.8, 0.05, 0);
                    guns.add(thrown);
                }
                long a = System.nanoTime();
                for (var g : guns) NativeWorlds.enqueue(g);
                long b = System.nanoTime();
                NativeWorlds.flush(level);
                long c = System.nanoTime();
                if (tick % 10 == 9 || tick >= 158 && tick <= 175) log.append(String.format(java.util.Locale.ROOT, "tick %d enqueue %.2f ms flush %.2f ms | %s%n", tick + 1, (b - a) / 1e6, (c - b) / 1e6, String.join(" | ", NativeWorlds.report().subList(1, NativeWorlds.report().size()))));
            }
            int fell = 0, still = 0, slow = 0, fast = 0;
            for (var g : guns) {
                double v = g.marker.getDeltaMovement().length() * 20, w = g.angular.length() * 20;
                if (g.marker.getY() < base.getY()) fell++;
                else if (v < .05 && w < .2) still++;
                else if (v < .5 && w < 2) slow++;
                else fast++;
            }
            log.append(String.format(java.util.Locale.ROOT, "end: %d fell off, %d still, %d slow, %d fast%n", fell, still, slow, fast));
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("physicalist-benchmark-guns.txt"), log.toString()); } catch (java.io.IOException ignored) {}
            h.succeed();
        } finally {
            for (var g : guns) g.marker.discard();
        }
    }

    /** A body owned by a plain marker entity, posed by the adapter like a mod's own entity would be. */
    private static final class Gun implements PhysicsBody {
        final Entity marker;
        org.joml.Quaternionf rot;
        Vec3 angular = Vec3.ZERO;
        private static final double[][] PARTS = {
                {0, 0, 0, .03, .05, .30}, {0, -.02, .38, .025, .025, .12}, {0, -.12, -.05, .025, .07, .03}, {0, -.10, .10, .02, .07, .025},
                {0, .02, -.36, .03, .06, .08}, {0, .07, .05, .015, .02, .10}, {.03, .0, .05, .01, .02, .04}, {-.03, 0, .05, .01, .02, .04},
                {0, -.04, .22, .03, .02, .06}, {0, .06, -.15, .01, .015, .03}, {0, .06, .25, .01, .015, .02}, {0, -.17, -.05, .02, .02, .02}};
        Gun(net.minecraft.server.level.ServerLevel level, Vec3 at, org.joml.Quaternionf rot) {
            marker = EntityType_MARKER(level);
            marker.setPos(at);
            level.addFreshEntity(marker);
            this.rot = rot;
        }
        private static Entity EntityType_MARKER(net.minecraft.server.level.ServerLevel level) { return net.minecraft.world.entity.EntityType.MARKER.create(level); }
        @Override public Entity entity() { return marker; }
        @Override public List<CompoundCollision.Box> collisionBoxes() {
            List<CompoundCollision.Box> out = new ArrayList<>();
            for (double[] p : PARTS) {
                var c = rot.transform(new org.joml.Vector3f((float) p[0], (float) p[1], (float) p[2]));
                var x = rot.transform(new org.joml.Vector3f(1, 0, 0)); var y = rot.transform(new org.joml.Vector3f(0, 1, 0)); var z = rot.transform(new org.joml.Vector3f(0, 0, 1));
                out.add(new CompoundCollision.Box(marker.position().add(c.x, c.y, c.z), new Vec3[]{new Vec3(x.x, x.y, x.z), new Vec3(y.x, y.y, y.z), new Vec3(z.x, z.y, z.z)}, new double[]{p[3], p[4], p[5]}));
            }
            return out;
        }
        @Override public Vec3 forward() { var v = rot.transform(new org.joml.Vector3f(0, 0, 1)); return new Vec3(v.x, v.y, v.z); }
        @Override public Vec3 up() { var v = rot.transform(new org.joml.Vector3f(0, 1, 0)); return new Vec3(v.x, v.y, v.z); }
        @Override public Vec3 angularVelocity() { return angular; }
        @Override public void setAngularVelocity(Vec3 a) { angular = a; }
        @Override public void rotate(Vec3 w, double fraction) {}
        @Override public org.joml.Quaternionf nativeOrientation() { return new org.joml.Quaternionf(rot); }
        @Override public void applyNativePose(Vec3 p, org.joml.Quaternionf q) { rot = new org.joml.Quaternionf(q).normalize(); marker.setPos(p); }
        @Override public long nativeGeometryRevision() { return 1; }
        @Override public double aerodynamicMass() { return 3.3; }
        @Override public boolean automaticAerodynamics() { return false; }
    }

    private static long mix(long hash, Vec3... values) {
        for (Vec3 v : values)
            for (double d : new double[]{v.x, v.y, v.z}) { hash ^= Double.doubleToLongBits(d); hash *= 1099511628211L; }
        return hash;
    }

    private BenchmarkGameTests() {}
}
