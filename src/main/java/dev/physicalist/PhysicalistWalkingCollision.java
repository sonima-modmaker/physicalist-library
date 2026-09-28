package dev.physicalist;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Supplies oriented assembly surfaces to Minecraft's ordinary walking solver. */
public final class PhysicalistWalkingCollision {
    public static List<VoxelShape> add(List<VoxelShape> original, Entity mover, Vec3 movement,
                                       AABB bounds, Level level) {
        if (mover == null || mover instanceof PhysicalBlockEntity || mover instanceof Projectile) return original;
        AABB query = bounds.expandTowards(movement).inflate(.05);
        List<VoxelShape> result = null;
        for (PhysicalBlockEntity assembly : level.getEntitiesOfClass(PhysicalBlockEntity.class,
                query.inflate(1), entity -> entity.isAlive() && entity.getBoundingBox().intersects(query))) {
            for (CompoundCollision.Box box : assembly.physicalistBody().collisionBoxes()) {
                if (!intersects(box, query)) continue;
                if (result == null) result = new ArrayList<>(original);
                addColumns(result, box, query);
            }
        }
        return result == null ? original : result;
    }

    private static boolean intersects(CompoundCollision.Box box, AABB query) {
        Vec3[] axes = box.axes();
        double[] half = box.half();
        double x = Math.abs(axes[0].x * half[0]) + Math.abs(axes[1].x * half[1])
                + Math.abs(axes[2].x * half[2]);
        double y = Math.abs(axes[0].y * half[0]) + Math.abs(axes[1].y * half[1])
                + Math.abs(axes[2].y * half[2]);
        double z = Math.abs(axes[0].z * half[0]) + Math.abs(axes[1].z * half[1])
                + Math.abs(axes[2].z * half[2]);
        Vec3 c = box.center();
        return new AABB(c.x - x, c.y - y, c.z - z, c.x + x, c.y + y, c.z + z)
                .intersects(query);
    }

    private static void addColumns(List<VoxelShape> shapes, CompoundCollision.Box box, AABB query) {
        Vec3 center = box.center();
        double rx = box.radius(new Vec3(1, 0, 0));
        double rz = box.radius(new Vec3(0, 0, 1));
        double step = .25;
        double minX = Math.max(center.x - rx, query.minX), maxX = Math.min(center.x + rx, query.maxX);
        double minZ = Math.max(center.z - rz, query.minZ), maxZ = Math.min(center.z + rz, query.maxZ);
        for (double x = minX; x < maxX; x += step) for (double z = minZ; z < maxZ; z += step) {
            double x1 = Math.min(x + step, maxX), z1 = Math.min(z + step, maxZ);
            double lo = -1e6, hi = 1e6;
            boolean hit = true;
            double dx = (x + x1) * .5 - center.x, dz = (z + z1) * .5 - center.z;
            for (int axis = 0; axis < 3; axis++) {
                Vec3 n = box.axes()[axis];
                double dot = dx * n.x + dz * n.z, half = box.half()[axis];
                if (Math.abs(n.y) < 1e-6) {
                    if (Math.abs(dot) > half) { hit = false; break; }
                } else {
                    double a = (-half - dot) / n.y, b = (half - dot) / n.y;
                    lo = Math.max(lo, Math.min(a, b));
                    hi = Math.min(hi, Math.max(a, b));
                }
            }
            if (hit && hi > lo && center.y + hi > query.minY && center.y + lo < query.maxY)
                shapes.add(Shapes.create(new AABB(x, center.y + lo, z,
                        x1, center.y + hi, z1)));
        }
    }

    private PhysicalistWalkingCollision() {}
}
