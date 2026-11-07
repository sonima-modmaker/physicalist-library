package dev.physicalist.soft;

import dev.physicalist.PhysicalistLibrary;
import dev.physicalist.nativecore.NativeSoft;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(PhysicalistLibrary.MOD_ID) @PrefixGameTestTemplate(false)
public final class SoftBodyGameTests {
    private static final SoftModelLoader.Source SAMPLES = SoftModelLoader.resources("/assets/physicalist_library/softbody");

    private static SoftModel sample(String name) throws Exception { return SoftModelLoader.load(SAMPLES, name + ".obj", null); }

    @GameTest(template = "empty")
    public static void samplesBuildWithDoors(GameTestHelper h) throws Exception {
        var plane = SoftStructure.build(sample("plane"), 1);
        var car = SoftStructure.build(sample("car"), 1);
        h.assertTrue(plane.nodes >= 80 && plane.nodes <= 900, "plane nodes " + plane.nodes);
        h.assertTrue(car.nodes >= 80 && car.nodes <= 900, "car nodes " + car.nodes);
        h.assertTrue(plane.doors.isEmpty(), "plane doors " + plane.doors.size());
        h.assertTrue(car.doors.size() == 2, "car doors " + car.doors.size());
        h.assertTrue(plane.aero.length / SoftStructure.AERO_STRIDE > plane.nodes / 3, "plane aero patches " + plane.aero.length / SoftStructure.AERO_STRIDE);
        h.assertTrue(plane.hasSeat() && plane.rotorCount() == 1, "plane seat " + plane.hasSeat() + " rotors " + plane.rotorCount());
        h.assertTrue(plane.rotors[9] > 0.8 && plane.rotors[9] < 1.1, "propeller radius " + plane.rotors[9]);
        h.assertTrue(plane.rotorAxis[2] > 0.99, "the propeller must pull forwards: " + Arrays.toString(plane.rotorAxis));
        int[] perChannel = new int[SoftStructure.CHANNELS];
        for (int i = 0; i < plane.aero.length; i += SoftStructure.AERO_STRIDE) if (plane.aero[i + 7] >= 0) perChannel[(int) plane.aero[i + 7]]++;
        h.assertTrue(Arrays.stream(perChannel).allMatch(c -> c >= 2), "control surface patches " + Arrays.toString(perChannel));
        int wheels = 0;
        for (int i = 0; i < plane.nodes; i++) if (plane.wheels[i * 5] >= 0) wheels++;
        h.assertTrue(wheels >= 3, "wheel nodes " + wheels);
        // Balance: the centre of mass sits ahead of the main wheels and behind the nose wheel.
        double cz = 0, mass = 0, mainZ = 1e9, noseZ = -1e9;
        for (int i = 0; i < plane.nodes; i++) {
            cz += plane.rest[i * 3 + 2] * plane.mass[i]; mass += plane.mass[i];
            if (plane.wheels[i * 5] >= 0) { mainZ = Math.min(mainZ, plane.rest[i * 3 + 2]); noseZ = Math.max(noseZ, plane.rest[i * 3 + 2]); }
        }
        cz /= mass;
        org.slf4j.LoggerFactory.getLogger("Physicalist").info("Plane: {} nodes, {} kg, centre of mass z {} (main wheels {}, nose wheel {}), power {} kW",
                plane.nodes, Math.round(mass), String.format("%.2f", cz), String.format("%.2f", mainZ), String.format("%.2f", noseZ), Math.round(plane.rotors[14] / 1000));
        h.assertTrue(cz > mainZ + 0.2 && cz < noseZ - 0.5, "centre of mass " + cz + " main " + mainZ + " nose " + noseZ);
        // The same model gives the same structure (server physics and client drawing must agree).
        var again = SoftStructure.build(SoftModel.decode(sample("plane").encode()), 1);
        h.assertTrue(Arrays.equals(again.rest, plane.rest) && Arrays.equals(again.anchorNodes, plane.anchorNodes), "structure is not deterministic");
        // Every render vertex sits exactly where the model put it when the nodes are at rest.
        var m = sample("car");
        double[][] placed = SoftStructure.placed(m, 1);
        double[] p = new double[3], worst = {0};
        for (int k = 0; k < m.meshes.size(); k++) for (int v = 0; v < m.meshes.get(k).vertices(); v++) {
            int a = car.meshStart[k] + v;
            SoftStructure.pointInFrame(car.rest, car.anchorNodes[a * 3], car.anchorNodes[a * 3 + 1], car.anchorNodes[a * 3 + 2], car.anchorCoords[a * 3], car.anchorCoords[a * 3 + 1], car.anchorCoords[a * 3 + 2], p);
            worst[0] = Math.max(worst[0], Math.abs(p[0] - placed[k][v * 3]) + Math.abs(p[1] - placed[k][v * 3 + 1]) + Math.abs(p[2] - placed[k][v * 3 + 2]));
        }
        h.assertTrue(worst[0] < 1e-3, "pinned vertex off by " + worst[0]);
        h.succeed();
    }

    /** glTF with an embedded buffer and a Minecraft JSON model, from memory. */
    @GameTest(template = "empty")
    public static void gltfAndJsonLoad(GameTestHelper h) throws Exception {
        var buffer = java.nio.ByteBuffer.allocate(4 * 3 * 4 + 6 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[][] quad = {{0, 0, 0}, {2, 0, 0}, {2, 1, 0}, {0, 1, 0}};
        for (var v : quad) for (float f : v) buffer.putFloat(f);
        for (short s : new short[]{0, 1, 2, 0, 2, 3}) buffer.putShort(s);
        String data = Base64.getEncoder().encodeToString(buffer.array());
        String gltf = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"name\":\"Door_Front\",\"mesh\":0,\"translation\":[0,1,0]}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1,\"material\":0}]}],"
                + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,0,0,1]}}],"
                + "\"buffers\":[{\"byteLength\":60,\"uri\":\"data:application/octet-stream;base64," + data + "\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":48},{\"buffer\":0,\"byteOffset\":48,\"byteLength\":12}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":4,\"type\":\"VEC3\"},{\"bufferView\":1,\"componentType\":5123,\"count\":6,\"type\":\"SCALAR\"}]}";
        Map<String, byte[]> files = new HashMap<>();
        files.put("a.gltf", gltf.getBytes(StandardCharsets.UTF_8));
        files.put("b.json", ("{\"elements\":[{\"name\":\"door\",\"from\":[0,0,0],\"to\":[16,16,2],\"faces\":{\"north\":{\"uv\":[0,0,16,16]},\"south\":{},\"east\":{},\"west\":{},\"up\":{},\"down\":{}}}]}").getBytes(StandardCharsets.UTF_8));
        SoftModelLoader.Source source = path -> { var b = files.get(path); if (b == null) throw new java.io.IOException(path); return b; };
        var a = SoftModelLoader.load(source, "a.gltf", null);
        h.assertTrue(a.triangles() == 2 && a.meshes.get(0).name().equals("Door_Front"), "glTF mesh " + a.triangles());
        h.assertTrue(a.meshes.get(0).positions()[1] == 1f, "glTF node translation not applied");
        h.assertTrue(a.materials.get(a.meshes.get(0).material()).color() == 0xFFFF0000, "glTF colour");
        var b = SoftModelLoader.load(source, "b.json", null);
        h.assertTrue(b.triangles() == 12, "JSON cube triangles " + b.triangles());
        h.succeed();
    }

    private static ServerLevel floor(GameTestHelper h, BlockPos at, int size) {
        var level = h.getLevel();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.getChunk((at.getX() >> 4) + x, (at.getZ() >> 4) + z);
        for (int x = -size; x <= size; x++) for (int z = -size; z <= size; z++) level.setBlock(at.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
        return level;
    }

    private static SoftBodyEntity spawn(ServerLevel level, String name, Vec3 base, Vec3 launch) throws Exception {
        byte[] blob = sample(name).encode();
        var e = SoftBodyEntity.spawn(level, blob, SoftStructure.build(SoftModel.decode(blob), 1), 1, base, 0, launch);
        level.addFreshEntity(e);
        return e;
    }

    private static void run(ServerLevel level, SoftBodyEntity e, int ticks) {
        for (int t = 0; t < ticks; t++) { SoftWorlds.unlock(level); e.tick(); }
    }

    /** Largest change of distance between node pairs from the rest shape: how bent the body is. */
    private static double distortion(SoftBodyEntity e) {
        var s = e.structure();
        double worst = 0;
        var random = new Random(1);
        for (int k = 0; k < 4000; k++) {
            int a = random.nextInt(s.nodes), b = random.nextInt(s.nodes);
            double d0 = Math.sqrt(sq(s.rest, a, s.rest, b)), d = Math.sqrt(sq(e.nodes, a, e.nodes, b));
            worst = Math.max(worst, Math.abs(d - d0));
        }
        return worst;
    }
    /** Which nodes moved most against the rest shape (best node pair) for failure messages. */
    private static String report(SoftBodyEntity e) {
        var s = e.structure();
        Set<Integer> doorNodes = new HashSet<>();
        for (var d : s.doors) for (int i : d.nodes()) doorNodes.add(i);
        double worst = 0; int wa = 0, wb = 0;
        for (int a = 0; a < s.nodes; a++) for (int b = a + 1; b < s.nodes; b++) {
            double x = Math.abs(Math.sqrt(sq(s.rest, a, s.rest, b)) - Math.sqrt(sq(e.nodes, a, e.nodes, b)));
            if (x > worst) { worst = x; wa = a; wb = b; }
        }
        double body = 0;
        for (int a = 0; a < s.nodes; a++) for (int b = a + 1; b < s.nodes; b++) {
            if (doorNodes.contains(a) || doorNodes.contains(b) || a >= s.nodes - 4 * s.doors.size() || b >= s.nodes - 4 * s.doors.size()) continue;
            body = Math.max(body, Math.abs(Math.sqrt(sq(s.rest, a, s.rest, b)) - Math.sqrt(sq(e.nodes, a, e.nodes, b))));
        }
        StringBuilder doors = new StringBuilder();
        for (var d : s.doors) {
            int home = (int) s.beams[d.latch() * SoftStructure.BEAM_STRIDE + 1];
            doors.append(" door ").append(d.name()).append(" free-home ").append(String.format("%.3f", Math.sqrt(sq(e.nodes, d.free(), e.nodes, home))))
                    .append(" hingeA drift ").append(String.format("%.3f", Math.abs(Math.sqrt(sq(e.nodes, d.hingeA(), e.nodes, home)) - Math.sqrt(sq(s.rest, d.hingeA(), s.rest, home)))))
                    .append(" nodes ").append(d.nodes().length).append(" L ").append(String.format("%.2f", d.length()));
        }
        doors.append(String.format(" rest %d (%.2f,%.2f,%.2f) %d (%.2f,%.2f,%.2f) now dist %.2f vs %.2f", wa, s.rest[wa * 3], s.rest[wa * 3 + 1], s.rest[wa * 3 + 2], wb, s.rest[wb * 3], s.rest[wb * 3 + 1], s.rest[wb * 3 + 2], Math.sqrt(sq(e.nodes, wa, e.nodes, wb)), Math.sqrt(sq(s.rest, wa, s.rest, wb))));
        return doors + " | worst pair " + wa + (doorNodes.contains(wa) ? "(door)" : wa >= s.nodes - 4 * s.doors.size() ? "(hinge)" : "") + "-" + wb + (doorNodes.contains(wb) ? "(door)" : wb >= s.nodes - 4 * s.doors.size() ? "(hinge)" : "")
                + ", body only " + body + ", nodes " + s.nodes + ", h " + s.spacing + ", mass " + s.nodeMass;
    }
    private static double sq(double[] p, int a, double[] q, int b) {
        double x = p[a * 3] - q[b * 3], y = p[a * 3 + 1] - q[b * 3 + 1], z = p[a * 3 + 2] - q[b * 3 + 2];
        return x * x + y * y + z * z;
    }
    private static double lowest(SoftBodyEntity e) { double y = 1e9; for (int i = 1; i < e.nodes.length; i += 3) y = Math.min(y, e.nodes[i]); return y; }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void carSettlesThenCrumplesAndRespawns(GameTestHelper h) throws Exception {
        if (!NativeSoft.AVAILABLE) { h.succeed(); return; }
        var at = h.absolutePos(new BlockPos(1, 100, 1));
        var level = floor(h, at, 8);
        double top = at.getY() + 1;
        var gentle = spawn(level, "car", new Vec3(at.getX() + .5, top + 0.4, at.getZ() + .5), Vec3.ZERO);
        try {
            run(level, gentle, 120);
            for (double v : gentle.nodes) h.assertTrue(Double.isFinite(v), "car node not finite");
            h.assertTrue(lowest(gentle) > top - 0.06, "car sank into the floor: " + (lowest(gentle) - top));
            double rest = distortion(gentle);
            h.assertTrue(rest < 0.08, "a gentle drop must not bend the car: " + rest + " " + report(gentle));
            // 30 m/s straight into the ground (a fall from about 45 m).
            gentle.discard();
            var crash = spawn(level, "car", new Vec3(at.getX() + .5, top + 3, at.getZ() + .5), new Vec3(0, -30, 0));
            run(level, crash, 60);
            double bent = distortion(crash);
            h.assertTrue(bent > 0.08, "a 30 m/s crash must crumple the car: " + bent);
            h.assertTrue(lowest(crash) > top - 0.1, "crashed car went through the floor: " + (lowest(crash) - top));
            crash.respawn();
            h.assertTrue(distortion(crash) < 1e-6, "respawn must restore the shape");
            run(level, crash, 2);
            crash.discard();
            h.succeed();
        } finally { gentle.discard(); }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void doorSwingsOutToNinetyDegrees(GameTestHelper h) throws Exception {
        if (!NativeSoft.AVAILABLE) { h.succeed(); return; }
        var at = h.absolutePos(new BlockPos(1, 100, 1));
        var level = floor(h, at, 8);
        double top = at.getY() + 1;
        var car = spawn(level, "car", new Vec3(at.getX() + .5, top + 0.3, at.getZ() + .5), Vec3.ZERO);
        try {
            run(level, car, 40);
            var s = car.structure();
            var door = s.doors.get(0);
            int b1 = (int) s.beams[door.latch() * SoftStructure.BEAM_STRIDE + 1];
            car.toggle(0);
            StringBuilder trace = new StringBuilder(" trace");
            for (int t = 0; t < 12; t++) { run(level, car, 5); trace.append(String.format(" %.2f", Math.sqrt(sq(car.nodes, door.free(), car.nodes, b1)))); }
            double open = Math.sqrt(sq(car.nodes, door.free(), car.nodes, b1));
            int b2 = (int) s.beams[(door.latch() - 1) * SoftStructure.BEAM_STRIDE + 1];
            trace.append(String.format(" free-inner %.3f (min %.3f) stop90 max %.3f", Math.sqrt(sq(car.nodes, door.free(), car.nodes, b2)),
                    s.beams[(door.latch() - 1) * SoftStructure.BEAM_STRIDE + 7], s.beams[(door.latch() - 2) * SoftStructure.BEAM_STRIDE + 8]));
            h.assertTrue(open > door.length() * 0.6, "door did not open:" + trace);
            // Fully out at 90 degrees the free edge is sqrt(2) door widths from where it was shut.
            h.assertTrue(open > door.length() * 0.6, "door did not open: " + open + " of " + door.length() + report(car));
            h.assertTrue(open < door.length() * Math.sqrt(2) * 1.12, "door swung past 90 degrees: " + open + " of " + door.length());
            car.toggle(0);
            StringBuilder back = new StringBuilder(" trace");
            for (int t = 0; t < 16; t++) { run(level, car, 5); back.append(String.format(" %.2f", Math.sqrt(sq(car.nodes, door.free(), car.nodes, b1)))); }
            double shut = Math.sqrt(sq(car.nodes, door.free(), car.nodes, b1));
            h.assertTrue(shut < door.length() * 0.25, "door did not close: " + shut + back);
            h.succeed();
        } finally { car.discard(); }
    }

    /** 50 m/s into a stone wall: beams snap and pieces come off; the solver stays finite and fast. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void planeHitsWallAndBreaks(GameTestHelper h) throws Exception {
        if (!NativeSoft.AVAILABLE) { h.succeed(); return; }
        var at = h.absolutePos(new BlockPos(1, 100, 1));
        var level = floor(h, at, 10);
        for (int x = -8; x <= 8; x++) for (int y = 1; y <= 6; y++) level.setBlock(at.offset(x, y, 9), Blocks.STONE.defaultBlockState(), 3);
        var plane = spawn(level, "plane", new Vec3(at.getX() + .5, at.getY() + 1.6, at.getZ() - 2.5), new Vec3(0, 0, 50));
        try {
            long start = System.nanoTime();
            run(level, plane, 40);
            double ms = (System.nanoTime() - start) / 1e6 / 40;
            for (double v : plane.nodes) h.assertTrue(Double.isFinite(v), "plane node not finite");
            int pieces = 0;
            if (plane.components != null) for (int c : plane.components) pieces = Math.max(pieces, c + 1);
            h.assertTrue(plane.version > 0, "nothing broke in a 50 m/s crash");
            h.assertTrue(pieces > 1, "the crash tore nothing off (" + pieces + " piece)");
            h.assertTrue(plane.getZ() < at.getZ() + 9.5, "the plane went through the wall: " + (plane.getZ() - at.getZ()));
            org.slf4j.LoggerFactory.getLogger("Physicalist").info("Soft-body crash: {} broken-beam events, {} pieces, {} ms per tick ({} nodes, {} beams)",
                    plane.version, pieces, String.format("%.2f", ms), plane.structure().nodes, plane.structure().beamCount());
            h.assertTrue(ms < 25, "soft-body step too slow: " + ms + " ms per tick");
            h.succeed();
        } finally { plane.discard(); }
    }

    private static double rpm(SoftBodyEntity e) { return e.rotors.length >= 2 ? e.rotors[1] * 60 / (2 * Math.PI) : 0; }

    /** On the ground: the engine spins the propeller up, the brakes hold the plane, released it rolls away forwards. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void planeEngineRunsAndPulls(GameTestHelper h) throws Exception {
        if (!NativeSoft.AVAILABLE) { h.succeed(); return; }
        var at = h.absolutePos(new BlockPos(1, 120, 1));
        var level = floor(h, at, 6);
        for (int x = -6; x <= 6; x++) for (int z = 7; z <= 60; z++) level.setBlock(at.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
        for (int cz = -1; cz <= 5; cz++) for (int cx = -1; cx <= 1; cx++) level.getChunk((at.getX() >> 4) + cx, (at.getZ() >> 4) + cz);
        var plane = spawn(level, "plane", new Vec3(at.getX() + .5, at.getY() + 1.05, at.getZ() + .5), Vec3.ZERO);
        plane.testPilot = true;
        try {
            run(level, plane, 30);
            double z0 = plane.getZ();
            plane.input(0, 0, 0, 1, true, true);
            run(level, plane, 60);
            double held = plane.getZ() - z0, thrust = plane.rotors[2];
            String trace = String.format(" rpm %.0f thrust %.0f N held %.2f m", rpm(plane), thrust, held);
            h.assertTrue(rpm(plane) > 1500, "the propeller did not spin up:" + trace);
            h.assertTrue(thrust > 1500, "no static thrust:" + trace);
            // Full power against the brakes: the propeller's wash over the wing unloads the wheels and they creep a bit.
            h.assertTrue(Math.abs(held) < 3.5, "the brakes did not hold:" + trace);
            plane.input(0, 0, 0, 1, true, false);
            run(level, plane, 50);
            double rolled = plane.getZ() - z0 - held;
            trace += String.format(" rolled %.1f m, x drift %.2f, lowest %.2f", rolled, plane.getX() - at.getX() - .5, lowest(plane) - at.getY() - 1);
            // Which nodes stand on the ground, and are they wheels?
            var st = plane.structure();
            int touching = 0, wheelsDown = 0;
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < st.nodes; i++) {
                if (!st.solid[i] || plane.nodes[i * 3 + 1] - st.radius > at.getY() + 1.03) continue;
                touching++;
                if (st.wheels[i * 5] >= 0) wheelsDown++;
                else where.append(String.format(" (%.1f,%.1f,%.1f)", st.rest[i * 3], st.rest[i * 3 + 1], st.rest[i * 3 + 2]));
            }
            trace += " touching " + touching + " wheels " + wheelsDown + where;
            double[] pr = plane.probe;
            if (pr.length >= 12) trace += String.format(" | along z: press %.0f lift %.0f skin %.0f total %.0f N, induced %.1f m/s", pr[2], pr[5], pr[8], pr[11], 0.0);
            org.slf4j.LoggerFactory.getLogger("Physicalist").info("Plane on the ground:{}", trace);
            h.assertTrue(rolled > 5, "full power did not pull the plane along:" + trace);
            h.assertTrue(distortion(plane) < 0.15, "rolling bent the plane: " + distortion(plane) + trace);
            h.succeed();
        } finally { plane.discard(); }
    }

    /**
     * The real (threaded) simulation, in the Nether so no other test steps it by hand: a plane flying low over stone
     * at full power must cost the server thread little, and the simulation must keep up with the game.
     */
    // Alone in its batch: other tests step their bodies by hand on the server thread, which slows the game's ticks.
    @GameTest(template = "empty", timeoutTicks = 300, batch = "threaded")
    public static void threadedPlaneBarelyCostsTheServer(GameTestHelper h) throws Exception {
        if (!NativeSoft.AVAILABLE) { h.succeed(); return; }
        var level = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        var at = new BlockPos(5000, 100, 5000);
        for (int cx = -2; cx <= 10; cx++) for (int cz = -2; cz <= 10; cz++) level.setChunkForced((at.getX() >> 4) + cx, (at.getZ() >> 4) + cz, true);
        for (int x = -12; x <= 12; x++) for (int z = -10; z <= 140; z++) level.setBlock(at.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
        for (int x = -12; x <= 12; x++) for (int z = -10; z <= 140; z++) for (int y = 1; y <= 12; y++) level.setBlock(at.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        var plane = spawn(level, "plane", new Vec3(at.getX() + .5, at.getY() + 4, at.getZ() + .5), new Vec3(0, 0, 30));
        plane.testPilot = true;
        plane.input(-0.3f, 0, 0, 1, true, false);
        long[] start = new long[4];
        h.runAfterDelay(20, () -> { start[0] = plane.ownNanos; start[1] = plane.ownTicks; start[2] = System.nanoTime(); start[3] = plane.applied == null ? 0 : plane.applied.tick(); });
        h.runAfterDelay(120, () -> {
            double ms = (plane.ownNanos - start[0]) / 1e6 / Math.max(1, plane.ownTicks - start[1]);
            double step = SoftWorlds.stepMillis(level);
            long behind = plane.applied == null ? -1 : level.getGameTime() - plane.applied.tick();
            String trace = String.format("server thread %.3f ms per soft-body tick, simulation step %.1f ms, snapshot tick %s, moved %.0f m",
                    ms, step, plane.applied == null ? "none" : plane.applied.tick(), plane.getZ() - at.getZ());
            org.slf4j.LoggerFactory.getLogger("Physicalist").info("Threaded soft bodies: {}", trace);
            plane.discard();
            for (int cx = -2; cx <= 10; cx++) for (int cz = -2; cz <= 10; cz++) level.setChunkForced((at.getX() >> 4) + cx, (at.getZ() >> 4) + cz, false);
            // The test server ticks as fast as it can: compare with real time, 50 ms per simulation step.
            double wall = (System.nanoTime() - start[2]) / 1e6, steps = plane.applied == null ? 0 : plane.applied.tick() - start[3];
            trace += String.format(", %.0f steps in %.0f ms", steps, wall);
            h.assertTrue(steps >= Math.min(100, wall / 50) * 0.8, "the simulation thread did not keep up: " + trace);
            h.assertTrue(ms < 1.5, "soft bodies cost the server thread too much: " + trace);
            h.succeed();
        });
    }

    /** In the air at cruise power with the controls centred it flies on: upright, at speed, holding its height roughly. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void planeFliesUnderPower(GameTestHelper h) throws Exception {
        if (!NativeSoft.AVAILABLE) { h.succeed(); return; }
        var at = h.absolutePos(new BlockPos(1, 220, 1));
        var level = floor(h, at.below(200), 2);
        var plane = spawn(level, "plane", new Vec3(at.getX() + .5, at.getY(), at.getZ() + .5), new Vec3(0, 0, 45));
        plane.ignoreTerrain = true;
        plane.testPilot = true;
        plane.input(0, 0, 0, 0.8f, true, false);
        for (int cz = -1; cz <= 20; cz++) for (int cx = -2; cx <= 2; cx++) level.getChunk((at.getX() >> 4) + cx, (at.getZ() >> 4) + cz);
        try {
            var s = plane.structure();
            StringBuilder trace = new StringBuilder();
            double y0 = plane.getY();
            double[] start = s.seat(plane.nodes);
            double speed = 0, up = 1;
            for (int t = 0; t < 16; t++) {
                double[] before = s.seat(plane.nodes);
                run(level, plane, 5);
                double[] seat = s.seat(plane.nodes), f = s.forward(plane.nodes);
                speed = Math.sqrt(sq(seat, 0, before, 0)) * 4;
                // How upright: the wing tips' height difference tells the bank, the forward axis the pitch.
                trace.append(String.format(" [%.0f m/s dy %.1f pitch %.0f rpm %.0f T %.0f]", speed, seat[1] - start[1], Math.toDegrees(Math.asin(Math.clamp(f[1], -1, 1))), rpm(plane), plane.rotors[2]));
                up = Math.min(up, 1 - Math.abs(f[1]));
            }
            double climb = plane.getY() - y0;
            org.slf4j.LoggerFactory.getLogger("Physicalist").info("Powered flight:{}", trace);
            h.assertTrue(speed > 25, "lost its speed:" + trace);
            h.assertTrue(climb > -40 && climb < 60, "did not hold its height (" + climb + " m):" + trace);
            h.assertTrue(up > 0.3, "flipped or dived:" + trace);
            h.assertTrue(distortion(plane) < 0.45, "flight bent the plane: " + distortion(plane));
            h.succeed();
        } finally { plane.discard(); }
    }

    /**
     * Wings spring: a hard upward blow on one wing tip flexes it and it springs back to its shape; only a blow far past
     * anything flying gives (a crash) bends it for good or tears it off.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void wingSpringsBackUnlessHitFarTooHard(GameTestHelper h) throws Exception {
        if (!NativeSoft.AVAILABLE) { h.succeed(); return; }
        var at = h.absolutePos(new BlockPos(1, 140, 1));
        var level = floor(h, at, 8);
        var plane = spawn(level, "plane", new Vec3(at.getX() + .5, at.getY() + 1.05, at.getZ() + .5), Vec3.ZERO);
        try {
            run(level, plane, 30);
            var s = plane.structure();
            double span = 0;
            for (int i = 0; i < s.nodes; i++) span = Math.max(span, s.rest[i * 3]);
            List<Integer> tip = new ArrayList<>();
            for (int i = 0; i < s.nodes; i++) if (s.solid[i] && s.rest[i * 3] > span * 0.7) tip.add(i);
            int[] who = tip.stream().mapToInt(Integer::intValue).toArray();
            double settled = distortion(plane);
            plane.kick(who, new double[]{0, 7, 0});
            double flexed = 0;
            for (int t = 0; t < 6; t++) { run(level, plane, 1); flexed = Math.max(flexed, distortion(plane)); }
            run(level, plane, 60);
            double after = distortion(plane);
            String trace = String.format("tip nodes %d, settled %.3f, flexed %.3f, after %.3f", who.length, settled, flexed, after);
            h.assertTrue(flexed > settled + 0.05, "the wing did not flex: " + trace);
            h.assertTrue(after < settled + 0.06, "the wing stayed bent after a blow it should spring back from: " + trace);
            int versionBefore = plane.version();
            plane.kick(who, new double[]{0, 90, 0});
            run(level, plane, 60);
            double wrecked = distortion(plane);
            trace += String.format(", after a 90 m/s blow %.3f (version %d -> %d)", wrecked, versionBefore, plane.version());
            org.slf4j.LoggerFactory.getLogger("Physicalist").info("Wing: {}", trace);
            h.assertTrue(wrecked > after + 0.3 || plane.version() > versionBefore, "a crash-sized blow left the wing whole: " + trace);
            h.succeed();
        } finally { plane.discard(); }
    }

    /** A plane dropped flat falls far slower than in vacuum (its wings catch the air) and keeps its shape. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void planeFallsWithAirDrag(GameTestHelper h) throws Exception {
        if (!NativeSoft.AVAILABLE) { h.succeed(); return; }
        var at = h.absolutePos(new BlockPos(1, 200, 1));
        var level = floor(h, at.below(150), 2);
        var plane = spawn(level, "plane", new Vec3(at.getX() + .5, at.getY(), at.getZ() + .5), new Vec3(0, 0, 40));
        plane.ignoreTerrain = true;
        // Entities that move into unloaded chunks are unloaded: keep the whole flight path loaded.
        for (int cz = -1; cz <= 12; cz++) for (int cx = -1; cx <= 1; cx++) level.getChunk((at.getX() >> 4) + cx, (at.getZ() >> 4) + cz);
        try {
            double y0 = plane.getY(), z0 = plane.getZ();
            StringBuilder trace = new StringBuilder();
            double area = 0;
            for (int i = 0; i < plane.structure().aero.length; i += SoftStructure.AERO_STRIDE) {
                var a = plane.structure().aero;
                int p = (int) a[i], q = (int) a[i + 1], r = (int) a[i + 2];
                var rest = plane.structure().rest;
                double[] e1 = {rest[q * 3] - rest[p * 3], rest[q * 3 + 1] - rest[p * 3 + 1], rest[q * 3 + 2] - rest[p * 3 + 2]}, e2 = {rest[r * 3] - rest[p * 3], rest[r * 3 + 1] - rest[p * 3 + 1], rest[r * 3 + 2] - rest[p * 3 + 2]};
                double cx = e1[1] * e2[2] - e1[2] * e2[1], cy = e1[2] * e2[0] - e1[0] * e2[2], cz = e1[0] * e2[1] - e1[1] * e2[0];
                area += a[i + 3] * Math.sqrt(cx * cx + cy * cy + cz * cz);
            }
            trace.append(String.format(" patch area %.1f mass %.0f;", area, plane.structure().totalMass()));
            var rest = plane.structure().rest;
            int nose = 0, tail = 0, tip = 0;
            for (int i = 0; i < plane.structure().nodes; i++) {
                if (rest[i * 3 + 2] > rest[nose * 3 + 2]) nose = i;
                if (rest[i * 3 + 2] < rest[tail * 3 + 2]) tail = i;
                if (rest[i * 3] > rest[tip * 3]) tip = i;
            }
            for (int t = 0; t < 12; t++) {
                double py = plane.getY(), pz = plane.getZ();
                run(level, plane, 5);
                double dx = plane.nodes[nose * 3] - plane.nodes[tail * 3], dy = plane.nodes[nose * 3 + 1] - plane.nodes[tail * 3 + 1], dz = plane.nodes[nose * 3 + 2] - plane.nodes[tail * 3 + 2];
                trace.append(String.format(" [v=(%.1f,%.1f) pitch %.0f yaw %.0f tipY %.1f]", (plane.getZ() - pz) * 4, (plane.getY() - py) * 4,
                        Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))), Math.toDegrees(Math.atan2(dx, dz)), plane.nodes[tip * 3 + 1] - plane.getY()));
            }
            double fall = y0 - plane.getY(), ahead = plane.getZ() - z0;
            Map<String, Integer> seen = new TreeMap<>();
            var b = plane.getBoundingBox().inflate(2);
            for (BlockPos q : BlockPos.betweenClosed(BlockPos.containing(b.minX, b.minY, b.maxZ - 1), BlockPos.containing(b.maxX, b.maxY, b.maxZ + 3)))
                if (!level.getBlockState(q).isAir()) seen.merge(level.getBlockState(q).getBlock().getDescriptionId(), 1, Integer::sum);
            trace.append(" blocks ahead ").append(seen);
            // In vacuum: 3 s -> 44 m down and 120 m ahead. A stable glider keeps most of its speed and is held up.
            h.assertTrue(fall < 38, "no lift or drag on the fall: " + fall + " m (ahead " + ahead + ")");
            h.assertTrue(ahead < 118 && ahead > 30, "drag on the flight is wrong: " + ahead + " m (fell " + fall + ")" + trace);
            h.assertTrue(distortion(plane) < 0.45, "flight alone bent the plane: " + distortion(plane) + " " + report(plane));
            h.succeed();
        } finally { plane.discard(); }
    }
}
