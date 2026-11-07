package dev.physicalist.soft;

import dev.physicalist.nativecore.NativeSoft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Soft bodies for other mods: build a {@link SoftStructure} from any model, put it in the world here (with road wheels
 * if it is a vehicle), feed it terrain and controls every tick, and read back the node positions, wheel states and
 * a rigid frame. Everything runs on Physicalist's soft-body thread.
 */
public final class SoftApi {
    public static boolean available() { return NativeSoft.AVAILABLE; }

    /** Puts a body in the level. nodes: world positions (xyz per node); wheels: {@link SoftStructure#roadWheel} specs back to back. */
    public static Body create(ServerLevel level, SoftStructure s, double[] nodes, double[] wheels, double friction, Vec3 velocity) {
        return create(level, s, nodes, wheels, friction, velocity, 1);
    }

    /** aero scales the air's forces on this body (below 1 for a car whose interior surfaces never meet the air). */
    public static Body create(ServerLevel level, SoftStructure s, double[] nodes, double[] wheels, double friction, Vec3 velocity, double aero) {
        var handle = SoftWorlds.add(level, s.nodeSpec(nodes), s.beams, s.aero, s.rotors, wheels == null ? new double[0] : wheels,
                new double[]{s.radius, friction, 0.005, SoftStructure.YIELD, SoftStructure.SHAPE_STIFFNESS, 0.03, 1, aero}, new int[0], s.beamCount());
        if (velocity != null && velocity.lengthSqr() > 0) {
            double[] v = {velocity.x, velocity.y, velocity.z};
            handle.op((w, b) -> NativeSoft.velocity0(w, b, v));
        }
        return new Body(s, handle, nodes.clone());
    }

    /** Lets the level's simulation take its next step; call once per server tick (repeated calls in a tick are ignored). */
    public static void advance(ServerLevel level) { SoftWorlds.advance(level); }

    /** Tests that run many ticks within one game tick: step now, on the calling thread (the level stays synchronous). */
    public static void stepNow(ServerLevel level) { SoftWorlds.unlock(level); SoftWorlds.advance(level); }

    public static final class Body {
        public final SoftStructure structure;
        private final SoftWorlds.Handle handle;
        private double[] last;
        private double lastMove = 1;
        private int quiet;

        private Body(SoftStructure structure, SoftWorlds.Handle handle, double[] nodes) {
            this.structure = structure;
            this.handle = handle;
            this.last = nodes;
        }

        /** The newest finished step, or null before the first. */
        public SoftWorlds.Snapshot latest() { return handle.latest(); }
        /** Gone: removed, or the simulation started over after a failure (build a new body then). */
        public boolean dead() { return handle.dead(); }
        public void remove() { handle.remove(); }

        /** Hands the blocks around the body to the solver (every tick while it moves, now and then at rest). */
        public void terrain(ServerLevel level, int tick) {
            var snap = handle.latest();
            if (snap != null) {
                double moved = 0;
                double[] n = snap.nodes();
                if (n.length == last.length) for (int i = 0; i < n.length; i++) moved = Math.max(moved, Math.abs(n[i] - last[i]));
                lastMove = moved;
                last = n;
                quiet = snap.sleeping() ? quiet + 1 : 0;
            }
            if (quiet > 40 && tick % 20 != 0) return;
            double[] boxes = SoftWorlds.terrain(level, bounds(last), 1.5 + Math.min(lastMove * 3, 6));
            handle.op((w, b) -> NativeSoft.terrain0(w, b, boxes));
        }

        /** {steer radians, drive torque N m, brake torque N m, strut lift m, spin rad/s or NaN to keep} per road wheel, in the order they were created. */
        public void wheels(double[] input) {
            double[] copy = input.clone();
            handle.op((w, b) -> NativeSoft.wheels0(w, b, copy));
        }

        /** Moves the body by an offset (a teleport), shape and speed kept. */
        public void move(Vec3 d) {
            double[] c = {d.x, d.y, d.z};
            handle.op((w, b) -> NativeSoft.move0(w, b, c));
            if (last != null) for (int i = 0; i < last.length; i += 3) { last[i] += d.x; last[i + 1] += d.y; last[i + 2] += d.z; }
        }

        /**
         * A teleport that also turns the body: every node is rotated by q about the pivot, then moved by the offset;
         * speeds turn with it. For placing a vehicle on a tilted ship or deck.
         */
        public void transform(Vec3 pivot, org.joml.Quaterniondc q, Vec3 offset) {
            double[] c = {pivot.x, pivot.y, pivot.z, q.x(), q.y(), q.z(), q.w(), offset.x, offset.y, offset.z};
            handle.op((w, b) -> NativeSoft.transform0(w, b, c));
            if (last != null) {
                var p = new org.joml.Vector3d();
                for (int i = 0; i < last.length; i += 3) {
                    p.set(last[i] - pivot.x, last[i + 1] - pivot.y, last[i + 2] - pivot.z);
                    q.transform(p);
                    last[i] = p.x + pivot.x + offset.x; last[i + 1] = p.y + pivot.y + offset.y; last[i + 2] = p.z + pivot.z + offset.z;
                }
            }
        }

        /**
         * Applies an impulse (newton-seconds) at a world point: the nodes nearest to it share the change in speed by
         * their mass. For weight from somebody standing on the body, a hit, a push.
         */
        public void push(SoftStructure s, Vec3 point, Vec3 impulse) {
            if (last == null || last.length < 3 || impulse.lengthSqr() < 1e-12) return;
            int count = Math.min(8, last.length / 3);
            int[] nearest = new int[count];
            double[] distance = new double[count];
            java.util.Arrays.fill(distance, Double.MAX_VALUE);
            for (int i = 0; i < last.length / 3; i++) {
                if (s.solid != null && i < s.solid.length && !s.solid[i]) continue;
                double dx = last[i * 3] - point.x, dy = last[i * 3 + 1] - point.y, dz = last[i * 3 + 2] - point.z, d = dx * dx + dy * dy + dz * dz;
                for (int k = 0; k < count; k++) if (d < distance[k]) {
                    System.arraycopy(distance, k, distance, k + 1, count - k - 1);
                    System.arraycopy(nearest, k, nearest, k + 1, count - k - 1);
                    distance[k] = d; nearest[k] = i;
                    break;
                }
            }
            double mass = 0;
            int found = 0;
            for (int k = 0; k < count; k++) if (distance[k] < Double.MAX_VALUE) { mass += s.mass != null && nearest[k] < s.mass.length ? s.mass[nearest[k]] : s.nodeMass; found++; }
            if (found == 0 || mass <= 0) return;
            int[] who = java.util.Arrays.copyOf(nearest, found);
            double[] dv = {impulse.x / mass, impulse.y / mass, impulse.z / mass};
            handle.op((w, b) -> NativeSoft.push0(w, b, who, dv));
        }

        public void velocity(Vec3 v) {
            double[] c = {v.x, v.y, v.z};
            handle.op((w, b) -> NativeSoft.velocity0(w, b, c));
        }

        private static AABB bounds(double[] n) {
            double lx = 1e30, ly = 1e30, lz = 1e30, hx = -1e30, hy = -1e30, hz = -1e30;
            for (int i = 0; i < n.length; i += 3) {
                lx = Math.min(lx, n[i]); ly = Math.min(ly, n[i + 1]); lz = Math.min(lz, n[i + 2]);
                hx = Math.max(hx, n[i]); hy = Math.max(hy, n[i + 1]); hz = Math.max(hz, n[i + 2]);
            }
            return new AABB(lx, ly, lz, hx, hy, hz);
        }
    }

    /**
     * Best rigid fit of the nodes to the rest shape (mass weighted): {centre of mass x,y,z, rest centre x,y,z,
     * quaternion x,y,z,w}; a world point is centre + R (restPoint - restCentre). q warm-starts the fit (or null).
     */
    public static double[] frame(SoftStructure s, double[] nodes, double[] q) {
        double total = 0;
        double[] cw = new double[3], cr = new double[3];
        for (int i = 0; i < s.nodes; i++) {
            if (!s.solid[i]) continue;
            double m = s.mass[i];
            total += m;
            for (int k = 0; k < 3; k++) { cw[k] += nodes[i * 3 + k] * m; cr[k] += s.rest[i * 3 + k] * m; }
        }
        for (int k = 0; k < 3; k++) { cw[k] /= total; cr[k] /= total; }
        // A = sum m (x - cw)(r - cr)^T, columns a[j] = sum m (x - cw) r_j.
        double[][] a = new double[3][3];
        for (int i = 0; i < s.nodes; i++) {
            if (!s.solid[i]) continue;
            double m = s.mass[i];
            for (int j = 0; j < 3; j++) {
                double r = s.rest[i * 3 + j] - cr[j];
                for (int k = 0; k < 3; k++) a[j][k] += m * (nodes[i * 3 + k] - cw[k]) * r;
            }
        }
        double[] quat = q != null && q.length == 4 ? q.clone() : new double[]{0, 0, 0, 1};
        for (int it = 0; it < 20; it++) {
            double[][] rm = columns(quat);
            double[] omega = new double[3];
            double denom = 0;
            for (int j = 0; j < 3; j++) {
                double[] c = cross(rm[j], a[j]);
                for (int k = 0; k < 3; k++) omega[k] += c[k];
                denom += dot(rm[j], a[j]);
            }
            denom = Math.abs(denom) + 1e-9;
            double wx = omega[0] / denom, wy = omega[1] / denom, wz = omega[2] / denom, w = Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (w < 1e-10) break;
            double sn = Math.sin(w / 2), cs = Math.cos(w / 2);
            double[] d = {wx / w * sn, wy / w * sn, wz / w * sn, cs};
            quat = mul(d, quat);
            double l = Math.sqrt(quat[0] * quat[0] + quat[1] * quat[1] + quat[2] * quat[2] + quat[3] * quat[3]);
            for (int k = 0; k < 4; k++) quat[k] /= l;
        }
        return new double[]{cw[0], cw[1], cw[2], cr[0], cr[1], cr[2], quat[0], quat[1], quat[2], quat[3]};
    }

    /** Columns of the rotation matrix of q (column j = R e_j). */
    private static double[][] columns(double[] q) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        return new double[][]{
                {1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w)},
                {2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w)},
                {2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y)}};
    }
    private static double[] mul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private SoftApi() {}
}
