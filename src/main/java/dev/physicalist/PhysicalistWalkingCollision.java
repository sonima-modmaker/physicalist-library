package dev.physicalist;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Supplies physical model surfaces to Minecraft's ordinary walking solver. */
public final class PhysicalistWalkingCollision {
    private static final int MAX_COLUMNS = 768;

    public static List<VoxelShape> add(List<VoxelShape> original, Entity mover, Vec3 movement,
                                       AABB bounds, Level level) {
        if (!(mover instanceof Player player) || player.isSpectator()) return original;
        AABB query = bounds.inflate(.06);
        List<VoxelShape> result = null;
        int columns = 0;
        for (Entity candidate : level.getEntities(mover, query,
                entity -> entity.isAlive() && entity instanceof PhysicalistBodyProvider)) {
            PhysicsBody body = ((PhysicalistBodyProvider) candidate).physicalistBody();
            if (body == null) continue;
            for (CompoundCollision.Box box : body.collisionBoxes()) {
                if (!intersects(box, query)) continue;
                if (result == null) result = new ArrayList<>(original);
                columns = addColumns(result, box, query, columns);
                if (columns >= MAX_COLUMNS) return result;
            }
        }
        return result == null ? original : result;
    }

    /** A player should push only the body that actually blocked horizontal movement. */
    public static void pushBlocked(Player player, Vec3 wanted, Vec3 resolved) {
        if (player.level().isClientSide || player.isSpectator()) return;
        Vec3 blocked = new Vec3(wanted.x - resolved.x, 0, wanted.z - resolved.z);
        if (blocked.horizontalDistanceSqr() < 1e-5) return;
        AABB atContact = player.getBoundingBox().move(resolved).inflate(.055);
        AABB query = atContact.inflate(.15);
        for (Entity candidate : player.level().getEntities(player, query,
                entity -> entity.isAlive() && entity instanceof PhysicalistBodyProvider)) {
            PhysicsBody body = ((PhysicalistBodyProvider) candidate).physicalistBody();
            if (body == null) continue;
            for (CompoundCollision.Box box : body.collisionBoxes()) {
                if (!intersects(box, query)) continue;
                CompoundCollision.Contact contact = CompoundCollision.contact(box,
                        CompoundCollision.box(atContact));
                if (contact == null || Math.abs(contact.normal().y) > .45) continue;
                double effort = blocked.dot(contact.normal());
                if (effort <= .008) continue;
                double mass = Math.max(.5, body.aerodynamicMass());
                double speed = Math.min(.12, effort * .65) / Math.sqrt(mass);
                candidate.setDeltaMovement(candidate.getDeltaMovement()
                        .add(contact.normal().scale(speed)));
                candidate.hasImpulse = true;
                Vec3 arm = contact.point().subtract(candidate.position());
                Vec3 spin = arm.cross(contact.normal().scale(speed / Math.max(1, mass)));
                body.setAngularVelocity(body.angularVelocity().add(spin.scale(.08)));
                break;
            }
        }
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
        return c.x + x > query.minX && c.x - x < query.maxX
                && c.y + y > query.minY && c.y - y < query.maxY
                && c.z + z > query.minZ && c.z - z < query.maxZ;
    }

    private static int addColumns(List<VoxelShape> shapes, CompoundCollision.Box box, AABB query,
                                  int columns) {
        Vec3 center = box.center();
        double rx = box.radius(new Vec3(1, 0, 0));
        double rz = box.radius(new Vec3(0, 0, 1));
        double step = Math.clamp(Math.min(box.half()[0], box.half()[2]) * 1.5, .125, .25);
        double minX = Math.max(center.x - rx, query.minX), maxX = Math.min(center.x + rx, query.maxX);
        double minZ = Math.max(center.z - rz, query.minZ), maxZ = Math.min(center.z + rz, query.maxZ);
        Vec3[] axes = box.axes();
        double[] half = box.half();
        for (double x = minX; x < maxX; x += step) for (double z = minZ; z < maxZ; z += step) {
            if (columns >= MAX_COLUMNS) return columns;
            double x1 = Math.min(x + step, maxX), z1 = Math.min(z + step, maxZ);
            double lo = -1e6, hi = 1e6;
            boolean hit = true;
            double dx = (x + x1) * .5 - center.x, dz = (z + z1) * .5 - center.z;
            for (int axis = 0; axis < 3; axis++) {
                Vec3 n = axes[axis];
                double dot = dx * n.x + dz * n.z;
                // A thin wing may only graze the edge of a cell. Account for
                // its full footprint instead of sampling only the cell centre.
                double spread = Math.abs(n.x) * (x1 - x) * .5
                        + Math.abs(n.z) * (z1 - z) * .5;
                if (Math.abs(n.y) < 1e-6) {
                    if (Math.abs(dot) > half[axis] + spread) { hit = false; break; }
                } else {
                    double a = (-half[axis] - spread - dot) / n.y;
                    double b = (half[axis] + spread - dot) / n.y;
                    lo = Math.max(lo, Math.min(a, b));
                    hi = Math.min(hi, Math.max(a, b));
                }
            }
            if (hit && hi > lo && center.y + hi > query.minY && center.y + lo < query.maxY) {
                shapes.add(Shapes.create(new AABB(x, Math.max(query.minY, center.y + lo), z,
                        x1, Math.min(query.maxY, center.y + hi), z1)));
                columns++;
            }
        }
        return columns;
    }

    private PhysicalistWalkingCollision() {}
}
