package dev.physicalist;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SupportFootprint {
        private double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        public void include(CompoundCollision.BoxView part, AABB obstacle) {
            // Only the bottom contact patch can support weight. The complete projected
            // bounds incorrectly put a tilted body's centre over a single touching corner.
            double low = part.center().y - part.radius(new Vec3(0, 1, 0));
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 8; corner++) {
                Vec3 point = part.center();
                for (int axis = 0; axis < 3; axis++)
                    point = point.add(part.axes()[axis].scale(part.half()[axis]
                            * ((corner & (1 << axis)) == 0 ? -1 : 1)));
                if (point.y > low + .08) continue;
                minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
                minZ = Math.min(minZ, point.z); maxZ = Math.max(maxZ, point.z);
            }
            AABB bounds = new AABB(minX - .005, low, minZ - .005,
                    maxX + .005, low + .08, maxZ + .005);
            double x0 = Math.max(bounds.minX, obstacle.minX);
            double x1 = Math.min(bounds.maxX, obstacle.maxX);
            double z0 = Math.max(bounds.minZ, obstacle.minZ);
            double z1 = Math.min(bounds.maxZ, obstacle.maxZ);
            if (x1 <= x0 || z1 <= z0) return;
            this.minX = Math.min(this.minX, x0); this.maxX = Math.max(this.maxX, x1);
            this.minZ = Math.min(this.minZ, z0); this.maxZ = Math.max(this.maxZ, z1);
        }

        public boolean supports(Vec3 center) {
            return center.x >= minX - .08 && center.x <= maxX + .08
                    && center.z >= minZ - .08 && center.z <= maxZ + .08;
        }
    }
