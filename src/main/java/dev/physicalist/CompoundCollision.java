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
    private static Vec3[] axes(BoxView a, BoxView b) {
        Vec3[] result = new Vec3[15];
        int count = 0;
        for (Vec3 axis : a.axes()) result[count++] = axis;
        for (Vec3 axis : b.axes()) result[count++] = axis;
        for (Vec3 x : a.axes()) for (Vec3 y : b.axes()) {
            Vec3 cross = x.cross(y);
            if (cross.lengthSqr() > 1e-10) result[count++] = cross.normalize();
        }
        return result;
    }
    public static Contact contact(BoxView a, BoxView b) {
        for (double h : a.half()) if (h <= 0) return null;
        for (double h : b.half()) if (h <= 0) return null;
        Vec3 delta = a.center().subtract(b.center()), normal = null;
        double depth = Double.POSITIVE_INFINITY;
        for (Vec3 axis : axes(a, b)) {
            if (axis == null) continue;
            double overlap = a.radius(axis) + b.radius(axis) - Math.abs(delta.dot(axis));
            if (overlap <= 0) return null;
            if (overlap < depth) {
                depth = overlap;
                normal = delta.dot(axis) >= 0 ? axis : axis.scale(-1);
            }
        }
        return new Contact(normal, a.support(normal.scale(-1)), depth);
    }
    /** Continuous SAT for translation: catches thin surfaces crossed between solver steps. */
    public static Contact swept(BoxView start, BoxView end, BoxView obstacle) {
        for (double h : start.half()) if (h <= 0) return null;
        for (double h : end.half()) if (h <= 0) return null;
        Vec3 travel = end.center().subtract(start.center());
        if (travel.lengthSqr() < 1e-14) return null;
        double enter = 0, exit = 1;
        Vec3 normal = null;
        Vec3 delta = start.center().subtract(obstacle.center());
        for (Vec3 axis : axes(start, obstacle)) {
            if (axis == null) continue;
            double radius = start.radius(axis) + obstacle.radius(axis);
            double position = delta.dot(axis), speed = travel.dot(axis);
            if (Math.abs(speed) < 1e-12) {
                if (Math.abs(position) > radius) return null;
                continue;
            }
            double first = (-radius - position) / speed, last = (radius - position) / speed;
            if (first > last) { double swap = first; first = last; last = swap; }
            if (first >= enter) { enter = first; normal = speed > 0 ? axis.scale(-1) : axis; }
            exit = Math.min(exit, last);
            if (enter > exit) return null;
        }
        if (normal == null || enter < 0 || enter > 1 || exit < 0) return null;
        Box atHit = new Box(start.center().add(travel.scale(enter)), start.axes(), start.half());
        return new Contact(normal, atHit.support(normal.scale(-1)),
                Math.max(0, -travel.dot(normal) * (1 - enter)) + .0001);
    }
    private CompoundCollision() {}
}
