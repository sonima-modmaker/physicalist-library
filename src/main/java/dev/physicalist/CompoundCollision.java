package dev.physicalist;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Separating-axis contact between oriented model volumes and world/entity volumes. */
public final class CompoundCollision {
    /** Implement this directly in an adapter to avoid allocating converted boxes. */
    public interface BoxView {
        Vec3 center();
        Vec3[] axes();
        double[] half();
        default double radius(Vec3 n) {
            double radius = 0;
            for (int i = 0; i < 3; i++) radius += half()[i] * Math.abs(axes()[i].dot(n));
            return radius;
        }
        default Vec3 support(Vec3 n) {
            Vec3 point = center();
            for (int i = 0; i < 3; i++) {
                double dot = axes()[i].dot(n);
                if (Math.abs(dot) > 1e-5)
                    point = point.add(axes()[i].scale(Math.copySign(half()[i], dot)));
            }
            return point;
        }
    }
    public record Box(Vec3 center, Vec3[] axes, double[] half) implements BoxView {}
    public record Contact(Vec3 normal, Vec3 point, double depth) {}
    public static Box box(AABB b) {
        return new Box(b.getCenter(),
                new Vec3[]{new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)},
                new double[]{b.getXsize() / 2, b.getYsize() / 2, b.getZsize() / 2});
    }
    /** Conservative world bounds used before the more expensive oriented SAT test. */
    public static AABB bounds(BoxView box) {
        Vec3[] axes = box.axes();
        double[] half = box.half();
        double x = radius(axes, half, 1, 0, 0);
        double y = radius(axes, half, 0, 1, 0);
        double z = radius(axes, half, 0, 0, 1);
        Vec3 c = box.center();
        return new AABB(c.x - x, c.y - y, c.z - z, c.x + x, c.y + y, c.z + z);
    }
    private static double radius(Vec3[] axes, double[] half, double x, double y, double z) {
        return half[0] * Math.abs(axes[0].x * x + axes[0].y * y + axes[0].z * z)
                + half[1] * Math.abs(axes[1].x * x + axes[1].y * y + axes[1].z * z)
                + half[2] * Math.abs(axes[2].x * x + axes[2].y * y + axes[2].z * z);
    }

    private static boolean overlapsAabb(BoxView a, BoxView b, Vec3 travel) {
        Vec3[] aa = a.axes(), ba = b.axes();
        double[] ah = a.half(), bh = b.half();
        Vec3 ac = a.center(), bc = b.center();
        double x = radius(aa, ah, 1, 0, 0) + radius(ba, bh, 1, 0, 0);
        double y = radius(aa, ah, 0, 1, 0) + radius(ba, bh, 0, 1, 0);
        double z = radius(aa, ah, 0, 0, 1) + radius(ba, bh, 0, 0, 1);
        return Math.min(ac.x, ac.x + travel.x) - bc.x <= x
                && Math.max(ac.x, ac.x + travel.x) - bc.x >= -x
                && Math.min(ac.y, ac.y + travel.y) - bc.y <= y
                && Math.max(ac.y, ac.y + travel.y) - bc.y >= -y
                && Math.min(ac.z, ac.z + travel.z) - bc.z <= z
                && Math.max(ac.z, ac.z + travel.z) - bc.z >= -z;
    }
    private static boolean sweptOverlapsAabb(BoxView start, BoxView end, BoxView obstacle) {
        Vec3 ac = start.center(), ec = end.center(), bc = obstacle.center();
        Vec3[] sa = start.axes(), ea = end.axes(), ba = obstacle.axes();
        double[] sh = start.half(), eh = end.half(), bh = obstacle.half();
        double x = Math.max(radius(sa, sh, 1, 0, 0), radius(ea, eh, 1, 0, 0))
                + radius(ba, bh, 1, 0, 0);
        double y = Math.max(radius(sa, sh, 0, 1, 0), radius(ea, eh, 0, 1, 0))
                + radius(ba, bh, 0, 1, 0);
        double z = Math.max(radius(sa, sh, 0, 0, 1), radius(ea, eh, 0, 0, 1))
                + radius(ba, bh, 0, 0, 1);
        return Math.min(ac.x, ec.x) - bc.x <= x && Math.max(ac.x, ec.x) - bc.x >= -x
                && Math.min(ac.y, ec.y) - bc.y <= y && Math.max(ac.y, ec.y) - bc.y >= -y
                && Math.min(ac.z, ec.z) - bc.z <= z && Math.max(ac.z, ec.z) - bc.z >= -z;
    }
    public static Contact contact(BoxView a, BoxView b) {
        Vec3[] aa = a.axes(), ba = b.axes();
        double[] ah = a.half(), bh = b.half();
        for (double h : ah) if (h <= 0) return null;
        for (double h : bh) if (h <= 0) return null;
        Vec3 ac = a.center(), bc = b.center();
        double dx = ac.x - bc.x, dy = ac.y - bc.y, dz = ac.z - bc.z;
        if (!overlapsAabb(a, b, Vec3.ZERO)) return null;
        double nx = 0, ny = 0, nz = 0;
        double depth = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 15; i++) {
            double x, y, z;
            if (i < 3) {
                Vec3 axis = aa[i]; x = axis.x; y = axis.y; z = axis.z;
            } else if (i < 6) {
                Vec3 axis = ba[i - 3]; x = axis.x; y = axis.y; z = axis.z;
            } else {
                Vec3 first = aa[(i - 6) / 3], second = ba[(i - 6) % 3];
                x = first.y * second.z - first.z * second.y;
                y = first.z * second.x - first.x * second.z;
                z = first.x * second.y - first.y * second.x;
                double lengthSq = x * x + y * y + z * z;
                if (lengthSq <= 1e-10) continue;
                double invLength = 1 / Math.sqrt(lengthSq);
                x *= invLength; y *= invLength; z *= invLength;
            }
            double projection = dx * x + dy * y + dz * z;
            double overlap = radius(aa, ah, x, y, z) + radius(ba, bh, x, y, z)
                    - Math.abs(projection);
            if (overlap <= 0) return null;
            if (overlap < depth) {
                depth = overlap;
                double sign = projection >= 0 ? 1 : -1;
                nx = x * sign; ny = y * sign; nz = z * sign;
            }
        }
        Vec3 normal = new Vec3(nx, ny, nz);
        return new Contact(normal, a.support(normal.scale(-1)), depth);
    }
    /** Continuous SAT for translation: catches thin surfaces crossed between solver steps. */
    public static Contact swept(BoxView start, BoxView end, BoxView obstacle) {
        Vec3[] aa = start.axes(), ba = obstacle.axes();
        double[] ah = start.half(), bh = obstacle.half();
        for (double h : ah) if (h <= 0) return null;
        for (double h : end.half()) if (h <= 0) return null;
        Vec3 travel = end.center().subtract(start.center());
        if (travel.lengthSqr() < 1e-14) return null;
        if (!sweptOverlapsAabb(start, end, obstacle)) return null;
        double enter = 0, exit = 1;
        double nx = 0, ny = 0, nz = 0;
        boolean hasNormal = false;
        Vec3 ac = start.center(), bc = obstacle.center();
        double dx = ac.x - bc.x, dy = ac.y - bc.y, dz = ac.z - bc.z;
        for (int i = 0; i < 15; i++) {
            double x, y, z;
            if (i < 3) {
                Vec3 axis = aa[i]; x = axis.x; y = axis.y; z = axis.z;
            } else if (i < 6) {
                Vec3 axis = ba[i - 3]; x = axis.x; y = axis.y; z = axis.z;
            } else {
                Vec3 first = aa[(i - 6) / 3], second = ba[(i - 6) % 3];
                x = first.y * second.z - first.z * second.y;
                y = first.z * second.x - first.x * second.z;
                z = first.x * second.y - first.y * second.x;
                double lengthSq = x * x + y * y + z * z;
                if (lengthSq <= 1e-10) continue;
                double invLength = 1 / Math.sqrt(lengthSq);
                x *= invLength; y *= invLength; z *= invLength;
            }
            double radius = radius(aa, ah, x, y, z) + radius(ba, bh, x, y, z);
            double position = dx * x + dy * y + dz * z;
            double speed = travel.x * x + travel.y * y + travel.z * z;
            if (Math.abs(speed) < 1e-12) {
                if (Math.abs(position) > radius) return null;
                continue;
            }
            double first = (-radius - position) / speed, last = (radius - position) / speed;
            if (first > last) { double swap = first; first = last; last = swap; }
            if (first >= enter) {
                enter = first;
                double sign = speed > 0 ? -1 : 1;
                nx = x * sign; ny = y * sign; nz = z * sign;
                hasNormal = true;
            }
            exit = Math.min(exit, last);
            if (enter > exit) return null;
        }
        if (!hasNormal || enter < 0 || enter > 1 || exit < 0) return null;
        Vec3 normal = new Vec3(nx, ny, nz);
        Box atHit = new Box(start.center().add(travel.scale(enter)), start.axes(), start.half());
        return new Contact(normal, atHit.support(normal.scale(-1)),
                Math.max(0, -travel.dot(normal) * (1 - enter)) + .0001);
    }
    private CompoundCollision() {}
}
