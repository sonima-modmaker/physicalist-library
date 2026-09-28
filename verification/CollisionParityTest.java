import dev.physicalist.CompoundCollision;
import java.util.Random;
import net.minecraft.world.phys.Vec3;

/** Compare the allocation-light SAT with the previous implementation. */
public final class CollisionParityTest {
    private static Vec3[] axes(Random random) {
        double yaw = random.nextDouble() * Math.PI * 2;
        double pitch = random.nextDouble() * Math.PI * 2;
        double roll = random.nextDouble() * Math.PI * 2;
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double cr = Math.cos(roll), sr = Math.sin(roll);
        return new Vec3[]{
                new Vec3(cy * cr + sy * sp * sr, cp * sr, -sy * cr + cy * sp * sr),
                new Vec3(-cy * sr + sy * sp * cr, cp * cr, sy * sr + cy * sp * cr),
                new Vec3(sy * cp, -sp, cy * cp)};
    }

    private static CompoundCollision.Box box(Random random) {
        return new CompoundCollision.Box(
                new Vec3(random.nextDouble() * 6 - 3, random.nextDouble() * 6 - 3,
                        random.nextDouble() * 6 - 3), axes(random),
                new double[]{.01 + random.nextDouble(), .01 + random.nextDouble(),
                        .01 + random.nextDouble()});
    }

    private static Vec3[] referenceAxes(CompoundCollision.Box a, CompoundCollision.Box b) {
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

    private static CompoundCollision.Contact referenceContact(CompoundCollision.Box a,
                                                                CompoundCollision.Box b) {
        Vec3 delta = a.center().subtract(b.center()), normal = null;
        double depth = Double.POSITIVE_INFINITY;
        for (Vec3 axis : referenceAxes(a, b)) {
            if (axis == null) continue;
            double overlap = a.radius(axis) + b.radius(axis) - Math.abs(delta.dot(axis));
            if (overlap <= 0) return null;
            if (overlap < depth) {
                depth = overlap;
                normal = delta.dot(axis) >= 0 ? axis : axis.scale(-1);
            }
        }
        return new CompoundCollision.Contact(normal, a.support(normal.scale(-1)), depth);
    }

    private static CompoundCollision.Contact referenceSwept(CompoundCollision.Box start,
                                                               CompoundCollision.Box end,
                                                               CompoundCollision.Box obstacle) {
        Vec3 travel = end.center().subtract(start.center());
        if (travel.lengthSqr() < 1e-14) return null;
        double enter = 0, exit = 1;
        Vec3 normal = null;
        Vec3 delta = start.center().subtract(obstacle.center());
        for (Vec3 axis : referenceAxes(start, obstacle)) {
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
        var atHit = new CompoundCollision.Box(start.center().add(travel.scale(enter)),
                start.axes(), start.half());
        return new CompoundCollision.Contact(normal, atHit.support(normal.scale(-1)),
                Math.max(0, -travel.dot(normal) * (1 - enter)) + .0001);
    }

    public static void main(String[] args) {
        Random random = new Random(20260928);
        for (int i = 0; i < 20_000; i++) {
            var a = box(random);
            var b = box(random);
            var oldContact = referenceContact(a, b);
            var newContact = CompoundCollision.contact(a, b);
            if ((oldContact == null) != (newContact == null)
                    || oldContact != null && (Math.abs(oldContact.depth() - newContact.depth()) > 1e-8
                    || oldContact.normal().distanceTo(newContact.normal()) > 1e-8))
                throw new AssertionError("Contact parity mismatch at sample " + i);
            Vec3 travel = new Vec3(random.nextDouble() * 6 - 3,
                    random.nextDouble() * 6 - 3, random.nextDouble() * 6 - 3);
            var end = new CompoundCollision.Box(a.center().add(travel), a.axes(), a.half());
            var oldSweep = referenceSwept(a, end, b);
            var newSweep = CompoundCollision.swept(a, end, b);
            if ((oldSweep == null) != (newSweep == null)
                    || oldSweep != null && (Math.abs(oldSweep.depth() - newSweep.depth()) > 1e-8
                    || oldSweep.normal().distanceTo(newSweep.normal()) > 1e-8))
                throw new AssertionError("Swept parity mismatch at sample " + i);
        }
        System.out.println("PASS: 20,000 randomized oriented contact and swept-collision comparisons");
    }
}
