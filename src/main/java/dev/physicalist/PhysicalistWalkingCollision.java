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
import net.minecraft.core.Direction;

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
                entity -> entity.isAlive() && !(entity instanceof PhysicalChainEntity) && entity instanceof PhysicalistBodyProvider)) {
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

    /** Final guard for movement solvers that replace collectColliders' shape list. */
    public static Vec3 clipPlayerMovement(Player player, Vec3 wanted, Vec3 resolved) {
        if (player.isSpectator() || player.noPhysics) return resolved;
        AABB bounds = player.getBoundingBox();
        List<VoxelShape> shapes = add(List.of(), player, wanted,
                bounds.expandTowards(wanted).inflate(.06), player.level());
        if (shapes.isEmpty()) return resolved;
        // A moving/rotating body can raise its surface into the player's feet between
        // ticks. Vanilla axis clipping deliberately ignores already overlapping boxes,
        // so without recovering this shallow overlap the next step falls straight through.
        double lift = 0;
        if (wanted.y <= 0 && resolved.y <= 0) {
            for (VoxelShape shape : shapes) {
                if(shape.isEmpty())continue;
                AABB surface = shape.bounds();
                double height = surface.maxY - bounds.minY;
                if (height <= 1e-7 || height > .125 || surface.minY >= bounds.minY
                        || surface.maxX <= bounds.minX + .04 || surface.minX >= bounds.maxX - .04
                        || surface.maxZ <= bounds.minZ + .04 || surface.minZ >= bounds.maxZ - .04) continue;
                lift = Math.max(lift, height);
            }
            if (lift > 0) {
                if (clearOfBlocks(player, bounds.move(0, lift, 0).deflate(1e-6)))
                    bounds = bounds.move(0, lift, 0);
                else lift = 0;
            }
        }
        double y = Shapes.collide(Direction.Axis.Y, bounds, shapes, resolved.y);
        bounds = bounds.move(0, y, 0);
        double x = Shapes.collide(Direction.Axis.X, bounds, shapes, resolved.x);
        bounds = bounds.move(x, 0, 0);
        double z = Shapes.collide(Direction.Axis.Z, bounds, shapes, resolved.z);
        Vec3 clipped = new Vec3(x, y + lift, z);
        double step = player.maxUpStep();
        if (step > 0 && wanted.y <= 0 && (player.onGround() || y > resolved.y)
                && clipped.horizontalDistanceSqr() + 1e-8 < resolved.horizontalDistanceSqr()) {
            AABB original = player.getBoundingBox();
            AABB route = original.expandTowards(resolved.x, step, resolved.z).inflate(.06);
            List<VoxelShape> all = new ArrayList<>(add(List.of(), player, wanted, route, player.level()));
            for (VoxelShape shape : player.level().getBlockCollisions(player, route)) all.add(shape);
            double up = Shapes.collide(Direction.Axis.Y, original, all, step);
            AABB raised = original.move(0, up, 0);
            double sx = Shapes.collide(Direction.Axis.X, raised, all, resolved.x);
            raised = raised.move(sx, 0, 0);
            double sz = Shapes.collide(Direction.Axis.Z, raised, all, resolved.z);
            raised = raised.move(0, 0, sz);
            double down = Shapes.collide(Direction.Axis.Y, raised, all, -up + resolved.y);
            if (sx * sx + sz * sz > clipped.horizontalDistanceSqr() + 1e-8)
                return new Vec3(sx, up + down, sz);
        }
        return clipped;
    }

    private static boolean clearOfBlocks(Player player, AABB bounds) {
        for (VoxelShape shape : player.level().getBlockCollisions(player, bounds)) {
            if (Shapes.joinIsNotEmpty(shape, Shapes.create(bounds), net.minecraft.world.phys.shapes.BooleanOp.AND)) return false;
        }
        return true;
    }

    /** A player should push only the body that actually blocked horizontal movement. */
    public static void pushBlocked(Player player, Vec3 wanted, Vec3 resolved) {
        if (player.level().isClientSide || player.isSpectator()) return;
        Vec3 blocked = new Vec3(wanted.x - resolved.x, 0, wanted.z - resolved.z);
        if (blocked.horizontalDistanceSqr() < 1e-5) return;
        AABB atContact = player.getBoundingBox().move(resolved).inflate(.055);
        AABB query = atContact.inflate(.15);
        for (Entity candidate : player.level().getEntities(player, query,
                entity -> entity.isAlive() && !(entity instanceof PhysicalChainEntity) && entity instanceof PhysicalistBodyProvider)) {
            PhysicsBody body = ((PhysicalistBodyProvider) candidate).physicalistBody();
            if (body == null) continue;
            for (CompoundCollision.Box box : body.collisionBoxes()) {
                if (!intersects(box, query)) continue;
                // Standing/stepping feet are support contacts, never side-push motors.
                if (player.getBoundingBox().minY >= box.center().y + box.radius(new Vec3(0,1,0)) - .08) continue;
                CompoundCollision.Contact contact = CompoundCollision.contact(box,
                        CompoundCollision.box(atContact));
                if (contact == null || Math.abs(contact.normal().y) > .45) continue;
                double effort = blocked.dot(contact.normal());
                if (effort <= .008) continue;
                double mass = Math.max(.5, body.aerodynamicMass());
                double speed = Math.min(.12, effort * .65) / Math.sqrt(mass);
                if(candidate instanceof PhysicalChainEntity chain){chain.pushAt(contact.point(),contact.normal().scale(speed));break;}
                candidate.setDeltaMovement(candidate.getDeltaMovement()
                        .add(contact.normal().scale(speed)));
                candidate.hasImpulse = true;
                Vec3 arm = contact.point().subtract(body.centerOfMass());
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
        double minX = Math.max(center.x - rx, Math.floor(query.minX / step) * step), maxX = Math.min(center.x + rx, query.maxX);
        double minZ = Math.max(center.z - rz, Math.floor(query.minZ / step) * step), maxZ = Math.min(center.z + rz, query.maxZ);
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
            double ry = box.radius(new Vec3(0, 1, 0));
            lo = Math.max(lo, -ry); hi = Math.min(hi, ry);
            if (hit && hi > lo && center.y + hi > query.minY && center.y + lo < query.maxY) {
                if(x1-x<1e-6||z1-z<1e-6||hi-lo<1e-6)continue;
                var shape=Shapes.create(new AABB(x, center.y + lo, z,x1, center.y + hi, z1));
                if(!shape.isEmpty()){shapes.add(shape);columns++;}
            }
        }
        return columns;
    }

    /** Bodies move independently of Player.move, so recover overlaps after each player tick too. */
    public static void recoverOverlap(Player player) {
        if (player.isSpectator() || player.noPhysics) return;
        for (int pass = 0; pass < 4; pass++) {
            AABB bounds = player.getBoundingBox();
            var shapes = add(List.of(), player, Vec3.ZERO, bounds.inflate(.06), player.level());
            double lift = 0;
            Vec3 sideways = Vec3.ZERO;
            for (var shape : shapes) {
                if(shape.isEmpty())continue;
                AABB surface = shape.bounds();
                if (!surface.intersects(bounds.deflate(1e-5))) continue;
                double height = surface.maxY - bounds.minY;
                if (height > 0 && height <= .6 && surface.minY < bounds.minY
                        && surface.maxX > bounds.minX + .04 && surface.minX < bounds.maxX - .04
                        && surface.maxZ > bounds.minZ + .04 && surface.minZ < bounds.maxZ - .04) {
                    lift = Math.max(lift, height);
                }
            }
            if (lift == 0) for (Entity candidate : player.level().getEntities(player, bounds.inflate(.06),
                    entity -> entity.isAlive() && !(entity instanceof PhysicalChainEntity) && entity instanceof PhysicalistBodyProvider)) {
                PhysicsBody body = ((PhysicalistBodyProvider) candidate).physicalistBody();
                if (body == null) continue;
                for (var box : body.collisionBoxes()) {
                    var contact = CompoundCollision.contact(CompoundCollision.box(bounds), box);
                    if (contact != null && Math.abs(contact.normal().y) < .5
                            && contact.depth() > sideways.length())
                        sideways = contact.normal().scale(Math.min(.5, contact.depth() + 1e-5));
                }
            }
            Vec3 correction = lift > 0 ? new Vec3(0, lift, 0) : sideways;
            if (correction.lengthSqr() < 1e-10 || !clearOfBlocks(player, bounds.move(correction).deflate(1e-5))) break;
            player.setPos(player.position().add(correction));
            if (lift > 0) {
                player.setOnGround(true);
                player.fallDistance = 0;
                Vec3 velocity = player.getDeltaMovement();
                if (velocity.y < 0) player.setDeltaMovement(velocity.x, 0, velocity.z);
            }
        }
    }

    private PhysicalistWalkingCollision() {}
}
