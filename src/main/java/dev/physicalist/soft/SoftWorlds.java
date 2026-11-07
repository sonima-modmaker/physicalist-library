package dev.physicalist.soft;

import dev.physicalist.nativecore.NativeSoft;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * One native soft-body world per dimension, simulated on its own thread so soft bodies never cost the server tick.
 * The server only queues commands (terrain, controls, doors) and picks up the latest finished snapshot; every game
 * tick allows the simulation one more 50 ms step. If the simulation cannot keep up it runs slower than the game
 * (slow motion) instead of holding the game back.
 */
public final class SoftWorlds {
    public static final double GRAVITY = 9.81, AIR_DENSITY = 1.225;
    private static final double[] CALM = {0, 0, 0};
    private static final Map<ResourceKey<Level>, Sim> SIMS = new HashMap<>();

    /** A native call made on the simulation thread with the world and the body's native id. */
    public interface Op { void run(long world, long body); }

    /** What a body looked like after one simulation step; arrays are shared between snapshots while unchanged. */
    /** wheels: per road wheel {angle, omega, compression, grounded (1/0), load, centre x, y, z}. */
    public record Snapshot(long tick, double[] nodes, int version, boolean sleeping, int[] components, boolean[] watched, boolean[] beams, double[] rotors, double[] wheels, double[] probe) {}

    /** A body in a simulation. Only the simulation thread touches {@link #id}. */
    public static final class Handle {
        final double[] nodes, beams, aero, rotors, wheels, params;
        final int[] watch;
        final int beamCount;
        final Sim sim;
        long id;
        volatile Snapshot latest;
        volatile boolean dead;
        private int publishedVersion = -1;
        private Snapshot last;

        Handle(Sim sim, double[] nodes, double[] beams, double[] aero, double[] rotors, double[] wheels, double[] params, int[] watch, int beamCount) {
            this.sim = sim; this.nodes = nodes; this.beams = beams; this.aero = aero; this.rotors = rotors; this.wheels = wheels; this.params = params; this.watch = watch; this.beamCount = beamCount;
        }
        public Snapshot latest() { return latest; }
        public boolean dead() { return dead; }
        public void op(Op op) { sim.queue(() -> { if (id != 0) op.run(sim.world, id); }); }
        public void remove() {
            dead = true;
            sim.queue(() -> { if (id != 0) { NativeSoft.remove0(sim.world, id); sim.handles.remove(this); id = 0; } });
        }
    }

    static final class Sim implements Runnable {
        final String name;
        final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
        final List<Handle> handles = new ArrayList<>();
        final Object lock = new Object();
        long world = NativeSoft.createWorld0();
        long granted, done, lastGrant = Long.MIN_VALUE;
        /** Tests step the world themselves, on the server thread. */
        boolean manual;
        volatile boolean closed;
        volatile double stepMillis;
        Thread thread;

        Sim(String name) { this.name = name; }

        void start() {
            if (manual || thread != null) return;
            thread = new Thread(this, "Physicalist soft bodies " + name);
            thread.setDaemon(true);
            thread.start();
        }

        void queue(Runnable r) {
            commands.add(r);
            if (manual) return;
            synchronized (lock) { lock.notifyAll(); }
        }

        @Override public void run() {
            while (!closed) {
                boolean step;
                synchronized (lock) {
                    while (!closed && commands.isEmpty() && granted <= done) {
                        try { lock.wait(100); } catch (InterruptedException e) { return; }
                    }
                    step = granted > done;
                }
                if (closed) break;
                pump(step);
            }
        }

        /** Runs the queued commands, then (when allowed) one step and publishes every body's snapshot. */
        void pump(boolean step) {
            for (Runnable r; (r = commands.poll()) != null; ) {
                try { r.run(); }
                catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Soft-body command failed: {}", error.toString()); }
            }
            if (!step) return;
            long start = System.nanoTime();
            try {
                NativeSoft.step0(world, 0.05, SoftStructure.SUBSTEPS, GRAVITY, AIR_DENSITY, CALM);
            } catch (RuntimeException error) {
                // A body blew up (NaN). Start the dimension's soft bodies over rather than lose the solver.
                org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Soft-body step failed in {}, rebuilding its bodies: {}", name, error.toString());
                NativeSoft.destroyWorld0(world);
                world = NativeSoft.createWorld0();
                for (var h : handles) { h.id = 0; h.dead = true; }
                handles.clear();
                synchronized (lock) { done++; }
                return;
            }
            long tick;
            synchronized (lock) { tick = ++done; }
            for (var h : handles) publish(h, tick);
            stepMillis = (System.nanoTime() - start) / 1e6;
        }

        void add(Handle h) {
            queue(() -> {
                if (h.dead) return;
                h.id = NativeSoft.add0(world, h.nodes, h.beams, h.aero, h.rotors, h.wheels, h.params);
                if (h.id == 0) { h.dead = true; return; }
                handles.add(h);
                publish(h, done);
            });
        }

        private void publish(Handle h, long tick) {
            double[] out = NativeSoft.read0(world, h.id);
            int n = h.nodes.length / SoftStructure.NODE_STRIDE;
            if (out.length < n * 3 + 4) return;
            int version = (int) out[n * 3];
            boolean sleeping = out[n * 3 + 2] > 0;
            var last = h.last;
            if (last != null && sleeping && last.sleeping() && version == h.publishedVersion) return;
            int rotors = (int) out[n * 3 + 3];
            double[] rotor = Arrays.copyOfRange(out, n * 3 + 4, Math.min(out.length, n * 3 + 4 + rotors * 7));
            int[] components;
            boolean[] watched, beams;
            if (last == null || version != h.publishedVersion) {
                components = NativeSoft.components0(world, h.id);
                watched = new boolean[h.watch.length];
                for (int i = 0; i < watched.length; i++) watched[i] = NativeSoft.alive0(world, h.id, h.watch[i]);
                beams = NativeSoft.beams0(world, h.id);
                h.publishedVersion = version;
            } else {
                components = last.components(); watched = last.watched(); beams = last.beams();
            }
            int at = Math.min(out.length, n * 3 + 4 + rotors * 7);
            int wheelCount = at < out.length ? (int) out[at] : 0;
            double[] wheels = Arrays.copyOfRange(out, Math.min(out.length, at + 1), Math.min(out.length, at + 1 + wheelCount * 8));
            double[] probe = Arrays.copyOfRange(out, Math.min(out.length, at + 1 + wheelCount * 8), out.length);
            h.last = new Snapshot(tick, Arrays.copyOf(out, n * 3), version, sleeping, components, watched, beams, rotor, wheels, probe);
            h.latest = h.last;
        }

        /** Server tick: one more step allowed (at most a few ahead, or the simulation would race to catch up). */
        void advance(long gameTime) {
            if (manual) {
                if (gameTime == lastGrant) return;
                lastGrant = gameTime;
                pump(true);
                return;
            }
            start();
            synchronized (lock) {
                if (gameTime == lastGrant) return;
                lastGrant = gameTime;
                if (granted - done < 2) granted = Math.max(granted, done) + 1;
                lock.notifyAll();
            }
        }

        void close() {
            closed = true;
            synchronized (lock) { lock.notifyAll(); }
            if (thread != null) try { thread.join(5000); } catch (InterruptedException ignored) {}
            // Whatever is still queued (removals) no longer matters.
            commands.clear();
            for (var h : handles) { h.dead = true; h.id = 0; }
            NativeSoft.destroyWorld0(world);
        }
    }

    private static synchronized Sim sim(ServerLevel level) {
        return SIMS.computeIfAbsent(level.dimension(), k -> new Sim(k.location().toString()));
    }

    /** Puts a body into the dimension's simulation; its first snapshot follows once the simulation has it. */
    static Handle add(ServerLevel level, double[] nodes, double[] beams, double[] aero, double[] rotors, double[] wheels, double[] params, int[] watch, int beamCount) {
        var sim = sim(level);
        var h = new Handle(sim, nodes, beams, aero, rotors, wheels, params, watch, beamCount);
        sim.add(h);
        return h;
    }

    /** Called by every soft body every tick; only the first call in a game tick counts. */
    static void advance(ServerLevel level) { sim(level).advance(level.getGameTime()); }

    /** World collision boxes {min xyz, max xyz} in bounds grown by reach; sky sections are skipped whole. */
    public static double[] terrain(ServerLevel level, net.minecraft.world.phys.AABB bounds, double reach) {
        int x0 = (int) Math.floor(bounds.minX - reach), y0 = (int) Math.floor(bounds.minY - reach), z0 = (int) Math.floor(bounds.minZ - reach);
        int x1 = (int) Math.floor(bounds.maxX + reach), y1 = (int) Math.floor(bounds.maxY + reach), z1 = (int) Math.floor(bounds.maxZ + reach);
        long cells = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (cells > 400_000) return new double[0];
        y0 = Math.max(y0, level.getMinBuildHeight());
        y1 = Math.min(y1, level.getMaxBuildHeight() - 1);
        double[] out = new double[1024];
        int k = 0;
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
            if (!(level.getChunkSource().getChunkNow(cx, cz) instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) continue;
            for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                int index = chunk.getSectionIndexFromSectionY(sy);
                if (index < 0 || index >= chunk.getSections().length || chunk.getSections()[index].hasOnlyAir()) continue;
                int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
                for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) for (int y = ay; y <= by; y++) {
                    pos.set(x, y, z);
                    var state = chunk.getBlockState(pos);
                    if (state.isAir()) continue;
                    var shape = state.getCollisionShape(level, pos);
                    if (shape.isEmpty()) continue;
                    for (var b : shape.toAabbs()) {
                        if (k + 6 > out.length) out = Arrays.copyOf(out, out.length * 2);
                        out[k++] = x + b.minX; out[k++] = y + b.minY; out[k++] = z + b.minZ;
                        out[k++] = x + b.maxX; out[k++] = y + b.maxY; out[k++] = z + b.maxZ;
                    }
                }
            }
        }
        return Arrays.copyOf(out, k);
    }

    /** Milliseconds the last simulation step took (on its own thread). */
    public static double stepMillis(ServerLevel level) { return sim(level).stepMillis; }

    /** Tests tick bodies many times within one game tick: step on the calling thread and let the next call step again. */
    static synchronized void unlock(ServerLevel level) {
        var s = sim(level);
        if (!s.manual) {
            if (s.thread != null) {
                // Hand the world over to the test thread: stop the simulation thread first.
                s.closed = true;
                synchronized (s.lock) { s.lock.notifyAll(); }
                try { s.thread.join(5000); } catch (InterruptedException ignored) {}
                s.thread = null;
                s.closed = false;
            }
            s.manual = true;
        }
        s.lastGrant = Long.MIN_VALUE;
    }

    public static synchronized void close(ServerLevel level) {
        var s = SIMS.remove(level.dimension());
        if (s != null) s.close();
    }

    public static synchronized void closeAll() {
        for (var s : SIMS.values()) s.close();
        SIMS.clear();
    }

    private SoftWorlds() {}
}
