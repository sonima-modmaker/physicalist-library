package dev.physicalist.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.physicalist.PhysicalistLibrary;
import dev.physicalist.soft.SoftBodyEntity;
import dev.physicalist.soft.SoftStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * F3+B on a soft body: what the solver actually simulates. Collision spheres of the nodes (boxes, orange on wheels,
 * purple on the heavy engine), beams coloured from green (at rest length) to red (stretched or squashed 10%),
 * aerodynamic patches as short normals (yellow on control surfaces, turned by their deflection), the propeller disc
 * with its thrust as a red arrow (1 m per kN), and the seat.
 */
@EventBusSubscriber(modid = PhysicalistLibrary.MOD_ID, value = Dist.CLIENT)
public final class SoftBodyDebugRenderer {
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.getEntityRenderDispatcher().shouldRenderHitBoxes()) return;
        var camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        boolean any = false;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof SoftBodyEntity e) || e.shown() == null || e.distanceToSqr(camera) > 96 * 96) continue;
            var s = e.shown();
            var p = SoftBodyView.pose(e, s, partial, camera.x, camera.y, camera.z);
            if (p == null) continue;
            any = true;
            draw(pose, lines, s, e, p);
        }
        if (any) buffers.endBatch(RenderType.lines());
    }

    private static void draw(PoseStack pose, VertexConsumer lines, SoftStructure s, SoftBodyEntity e, SoftBodyView.Pose p) {
        double[] n = p.nodes(), rest = s.rest;
        boolean[] alive = e.beams();
        var last = pose.last();
        // Beams.
        for (int b = 0; b < s.beamCount(); b++) {
            if (alive != null && b < alive.length && !alive[b]) continue;
            int o = b * SoftStructure.BEAM_STRIDE, i = (int) s.beams[o], j = (int) s.beams[o + 1];
            if ((int) s.beams[o + 6] != 0) continue;
            double l = dist(n, i, n, j), l0 = dist(rest, i, rest, j);
            float strain = (float) Math.min(1, Math.abs(l - l0) / Math.max(l0, 1e-6) * 10);
            line(lines, last, n, i, j, strain, 1 - strain * 0.8f, 0.15f, 0.8f);
        }
        // Nodes.
        double r = s.radius;
        for (int i = 0; i < s.nodes; i++) {
            if (!s.solid[i]) continue;
            boolean wheel = s.wheels[i * 5] >= 0, heavy = s.mass[i] > s.nodeMass * 1.5;
            float red = wheel ? 1f : heavy ? 0.7f : 0.3f, green = wheel ? 0.55f : heavy ? 0.25f : 0.75f, blue = wheel ? 0.1f : 1f;
            LevelRenderer.renderLineBox(pose, lines, n[i * 3] - r, n[i * 3 + 1] - r, n[i * 3 + 2] - r, n[i * 3] + r, n[i * 3 + 1] + r, n[i * 3 + 2] + r, red, green, blue, wheel || heavy ? 0.8f : 0.25f);
        }
        // Aerodynamic patches.
        double[] q = new double[3], t = new double[3];
        for (int k = 0; k < s.aero.length; k += SoftStructure.AERO_STRIDE) {
            int a = (int) s.aero[k], b = (int) s.aero[k + 1], c = (int) s.aero[k + 2], ch = (int) s.aero[k + 7];
            SoftStructure.pointInFrame(n, a, b, c, (float) s.aero[k + 4], (float) s.aero[k + 5], (float) s.aero[k + 6], q);
            double nx = q[0] - n[a * 3], ny = q[1] - n[a * 3 + 1], nz = q[2] - n[a * 3 + 2];
            if (ch >= 0 && ch < p.controls().length) {
                SoftStructure.pointInFrame(n, a, b, c, (float) s.aero[k + 8], (float) s.aero[k + 9], (float) s.aero[k + 10], t);
                double d = p.controls()[ch], cs = Math.cos(d), sn = Math.sin(d);
                nx = nx * cs + (t[0] - n[a * 3]) * sn; ny = ny * cs + (t[1] - n[a * 3 + 1]) * sn; nz = nz * cs + (t[2] - n[a * 3 + 2]) * sn;
            }
            double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1e-9) continue;
            double cx = (n[a * 3] + n[b * 3] + n[c * 3]) / 3, cy = (n[a * 3 + 1] + n[b * 3 + 1] + n[c * 3 + 1]) / 3, cz = (n[a * 3 + 2] + n[b * 3 + 2] + n[c * 3 + 2]) / 3;
            double len = 0.25 / l;
            boolean control = ch >= 0;
            seg(lines, last, cx, cy, cz, cx + nx * len, cy + ny * len, cz + nz * len, control ? 1f : 0.2f, control ? 0.9f : 0.85f, control ? 0.1f : 1f, 0.9f);
        }
        // Propeller disc and thrust.
        int[] f = s.rotorFrame();
        if (f != null && s.rotorHub != null) {
            double[] hub = place(s, n, f, s.rotorHub), tip = place(s, n, f, new double[]{s.rotorHub[0] + s.rotorAxis[0], s.rotorHub[1] + s.rotorAxis[1], s.rotorHub[2] + s.rotorAxis[2]});
            double[] axis = SoftStructure.unit(new double[]{tip[0] - hub[0], tip[1] - hub[1], tip[2] - hub[2]});
            double[] u = SoftStructure.unit(SoftStructure.crossOf(axis, Math.abs(axis[1]) < 0.9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0}));
            double[] v = SoftStructure.crossOf(axis, u);
            double radius = s.rotors[9];
            for (int k = 0; k < 32; k++) {
                double a0 = 2 * Math.PI * k / 32, a1 = 2 * Math.PI * (k + 1) / 32;
                seg(lines, last, hub[0] + (u[0] * Math.cos(a0) + v[0] * Math.sin(a0)) * radius, hub[1] + (u[1] * Math.cos(a0) + v[1] * Math.sin(a0)) * radius, hub[2] + (u[2] * Math.cos(a0) + v[2] * Math.sin(a0)) * radius,
                        hub[0] + (u[0] * Math.cos(a1) + v[0] * Math.sin(a1)) * radius, hub[1] + (u[1] * Math.cos(a1) + v[1] * Math.sin(a1)) * radius, hub[2] + (u[2] * Math.cos(a1) + v[2] * Math.sin(a1)) * radius,
                        1f, 0.3f, 1f, 1f);
            }
            double thrust = p.rotors().length >= 3 ? p.rotors()[2] / 1000 : 0;
            seg(lines, last, hub[0], hub[1], hub[2], hub[0] + axis[0] * thrust, hub[1] + axis[1] * thrust, hub[2] + axis[2] * thrust, 1f, 0.1f, 0.1f, 1f);
            for (int i = 0; i < s.rotorCount() && i < 1; i++) for (int k = 0; k < (int) s.rotors[16]; k++) {
                int node = (int) s.rotors[17 + k];
                seg(lines, last, hub[0], hub[1], hub[2], n[node * 3], n[node * 3 + 1], n[node * 3 + 2], 1f, 0.3f, 1f, 0.5f);
            }
        }
        // Seat.
        double[] seat = s.seat(n);
        if (seat != null) LevelRenderer.renderLineBox(pose, lines, seat[0] - 0.25, seat[1], seat[2] - 0.25, seat[0] + 0.25, seat[1] + 0.05, seat[2] + 0.25, 1f, 1f, 1f, 1f);
    }

    private static double[] place(SoftStructure s, double[] n, int[] f, double[] restPoint) {
        double[] rest = s.rest;
        double[] c = SoftStructure.frameCoordinates(new double[]{rest[f[0] * 3], rest[f[0] * 3 + 1], rest[f[0] * 3 + 2]},
                new double[]{rest[f[1] * 3], rest[f[1] * 3 + 1], rest[f[1] * 3 + 2]}, new double[]{rest[f[2] * 3], rest[f[2] * 3 + 1], rest[f[2] * 3 + 2]}, restPoint);
        double[] out = new double[3];
        SoftStructure.pointInFrame(n, f[0], f[1], f[2], (float) c[0], (float) c[1], (float) c[2], out);
        return out;
    }

    private static double dist(double[] p, int a, double[] q, int b) {
        double x = p[a * 3] - q[b * 3], y = p[a * 3 + 1] - q[b * 3 + 1], z = p[a * 3 + 2] - q[b * 3 + 2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void line(VertexConsumer lines, PoseStack.Pose last, double[] n, int i, int j, float r, float g, float b, float a) {
        seg(lines, last, n[i * 3], n[i * 3 + 1], n[i * 3 + 2], n[j * 3], n[j * 3 + 1], n[j * 3 + 2], r, g, b, a);
    }

    private static void seg(VertexConsumer lines, PoseStack.Pose last, double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b, float a) {
        float dx = (float) (x1 - x0), dy = (float) (y1 - y0), dz = (float) (z1 - z0), l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-6f) return;
        dx /= l; dy /= l; dz /= l;
        lines.addVertex(last, (float) x0, (float) y0, (float) z0).setColor(r, g, b, a).setNormal(last, dx, dy, dz);
        lines.addVertex(last, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(last, dx, dy, dz);
    }

    private SoftBodyDebugRenderer() {}
}
