package dev.physicalist.soft;

import java.util.ArrayDeque;

/**
 * Client side: poses of a soft body stamped with the simulation step that made them, played back a little behind
 * the newest one at a steady pace. Packets arrive whenever the network delivers them; drawing from their own
 * timestamps (not from when they arrived) is what keeps a fast plane from shuddering.
 */
public final class SoftTimeline {
    /** Client ticks since start; advanced at the start of every client tick. */
    public static long clientTicks;
    private static final int KEEP = 24, WINDOW = 40;

    /** One pose: world node positions, per rotor {angle, omega, thrust, throttle, on}, control deflections. */
    public record Frame(long tick, double[] nodes, float[] rotors, float[] controls) {}

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final double[] lags = new double[WINDOW];
    private int lagCount, lagAt;
    /** Simulation tick shown = client tick - offset. */
    private double offset = Double.NaN;

    public void add(Frame f) {
        var last = frames.peekLast();
        if (last != null && f.tick() <= last.tick()) {
            // The simulation started over (a new world after a failure): forget the old timeline.
            if (f.tick() < last.tick() - 2) { frames.clear(); offset = Double.NaN; lagCount = 0; }
            else return;
        }
        frames.addLast(f);
        while (frames.size() > KEEP) frames.removeFirst();
        lags[lagAt] = clientTicks - f.tick();
        lagAt = (lagAt + 1) % WINDOW;
        lagCount = Math.min(lagCount + 1, WINDOW);
    }

    /** Once per client tick: drift the delay towards the latest packets plus a safety margin. */
    public void tick() {
        if (lagCount == 0) return;
        double worst = -Double.MAX_VALUE;
        for (int i = 0; i < lagCount; i++) worst = Math.max(worst, lags[i]);
        double target = worst + 1.3;
        if (Double.isNaN(offset) || Math.abs(target - offset) > 20) offset = target;
        else if (target > offset) offset += Math.min(target - offset, 0.25);
        else offset -= Math.min(offset - target, 0.02);
    }

    public boolean empty() { return frames.isEmpty(); }
    public Frame newest() { return frames.peekLast(); }

    /** Simulation time to show: partial is the render partial tick; 1 gives the end of the current tick. */
    public double time(float partial) { return Double.isNaN(offset) ? (frames.isEmpty() ? 0 : frames.peekLast().tick()) : clientTicks - 1 + partial - offset; }

    /**
     * Node positions at simulation time t into out (world), interpolated between the frames around t; a little
     * past the newest frame they carry on at its speed, then stop. Returns the fraction and frames used, for rotors
     * and controls, via {@link #around}.
     */
    public boolean sample(double t, double[] out) {
        var pair = around(t);
        if (pair == null) return false;
        Frame a = pair[0], b = pair[1];
        double[] p = a.nodes(), q = b.nodes();
        if (p.length != out.length || q.length != out.length) return false;
        double f = a == b ? 0 : (t - a.tick()) / (double) (b.tick() - a.tick());
        f = Math.min(f, 1 + 3.0 / Math.max(1, b.tick() - a.tick()));
        for (int i = 0; i < out.length; i++) out[i] = p[i] + (q[i] - p[i]) * f;
        return true;
    }

    /** Fraction between the two frames around t (may exceed 1 a little when extrapolating). */
    public double fraction(double t) {
        var pair = around(t);
        if (pair == null || pair[0] == pair[1]) return 0;
        return Math.min((t - pair[0].tick()) / (double) (pair[1].tick() - pair[0].tick()), 1);
    }

    /** The frames on either side of t: the two newest when t is past them, the oldest twice when before. */
    public Frame[] around(double t) {
        if (frames.isEmpty()) return null;
        Frame prev = null;
        for (var f : frames) {
            if (f.tick() >= t) return new Frame[]{prev == null ? f : prev, f};
            prev = f;
        }
        if (frames.size() == 1) return new Frame[]{prev, prev};
        var it = frames.descendingIterator();
        Frame last = it.next(), before = it.next();
        return new Frame[]{before, last};
    }
}
