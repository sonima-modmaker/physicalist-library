package dev.physicalist.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.physicalist.PhysicalistLibrary;
import dev.physicalist.soft.SoftBodyEntity;
import dev.physicalist.soft.SoftModel;
import dev.physicalist.soft.SoftStructure;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * A soft body's mesh on this client: decoded and pinned to the nodes off-thread, textures uploaded on first draw.
 * Control surfaces are drawn turned about their hinges and the propeller spinning about its hub.
 */
public final class SoftBodyView {
    private static final Map<SoftBodyEntity, SoftBodyView> VIEWS = new WeakHashMap<>();
    /** Above this the blades are a blur: they are drawn turning this fast plus a see-through disc. */
    private static final double SHOWN_SPIN = 26;
    private static int next;

    private final byte[] blob;
    private final CompletableFuture<Object[]> loading;
    private SoftModel model;
    private SoftStructure structure;
    private double[][] placed;
    private ResourceLocation[] textures;
    private ResourceLocation white;
    private boolean[] translucent;
    private boolean[][] hidden;
    private int hiddenVersion = -1;
    private int[] hiddenComponents;
    private double[] scratch = new double[0];
    private double propAngle;
    private long lastFrame;
    /** Road wheels (meshes named "wheel"): hub and radius in the rest shape, how far each has turned, last hub seen. */
    private double[][] wheelHub;
    private double[] wheelRadius, wheelAngle;
    private double[][] wheelLast;

    private SoftBodyView(byte[] blob, double scale) {
        this.blob = blob;
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                var m = SoftModel.decode(blob);
                return new Object[]{m, SoftStructure.build(m, scale), SoftStructure.placed(m, scale)};
            } catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Cannot show soft body: {}", error.toString());
                return null;
            }
        });
    }

    static SoftBodyView of(SoftBodyEntity e) {
        byte[] blob = e.blob();
        if (blob == null || blob.length == 0) return null;
        var view = VIEWS.get(e);
        if (view == null || view.blob != blob) {
            if (view != null) view.free();
            view = new SoftBodyView(blob, e.userScale());
            VIEWS.put(e, view);
        }
        if (view.ready() && e.shown() != view.structure) e.shown(view.structure);
        return view;
    }

    public static void release(SoftBodyEntity e) {
        var view = VIEWS.remove(e);
        if (view != null) view.free();
    }

    private void free() {
        if (textures == null) return;
        var manager = Minecraft.getInstance().getTextureManager();
        for (var t : textures) if (t != null && t.getPath().startsWith("soft/")) manager.release(t);
        if (white != null) manager.release(white);
        textures = null;
    }

    private boolean ready() {
        if (structure != null) return true;
        if (!loading.isDone()) return false;
        Object[] r = loading.getNow(null);
        if (r == null) return false;
        model = (SoftModel) r[0];
        structure = (SoftStructure) r[1];
        placed = (double[][]) r[2];
        int meshes = model.meshes.size();
        wheelHub = new double[meshes][];
        wheelRadius = new double[meshes];
        wheelAngle = new double[meshes];
        wheelLast = new double[meshes][];
        for (int k = 0; k < meshes; k++) {
            String name = model.meshes.get(k).name().toLowerCase(Locale.ROOT);
            if (!name.contains("wheel") || placed[k].length < 9) continue;
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            for (int i = 0; i < placed[k].length; i += 3) for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], placed[k][i + a]); hi[a] = Math.max(hi[a], placed[k][i + a]); }
            wheelHub[k] = new double[]{(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
            wheelRadius[k] = Math.max(0.05, Math.max(hi[1] - lo[1], hi[2] - lo[2]) / 2);
        }
        var manager = Minecraft.getInstance().getTextureManager();
        textures = new ResourceLocation[model.materials.size()];
        translucent = new boolean[textures.length];
        var img = new NativeImage(1, 1, false);
        img.setPixelRGBA(0, 0, -1);
        white = ResourceLocation.fromNamespaceAndPath(PhysicalistLibrary.MOD_ID, "soft/white_" + next++);
        manager.register(white, new DynamicTexture(img));
        for (int i = 0; i < textures.length; i++) {
            var m = model.materials.get(i);
            translucent[i] = (m.color() >>> 24) < 250;
            NativeImage image = null;
            if (m.png() != null) try { image = NativeImage.read(new ByteArrayInputStream(m.png())); } catch (Exception ignored) {}
            if (image == null) { textures[i] = white; continue; }
            if (!translucent[i]) for (int y = 0; y < image.getHeight() && !translucent[i]; y += Math.max(1, image.getHeight() / 64))
                for (int x = 0; x < image.getWidth(); x += Math.max(1, image.getWidth() / 64)) {
                    int a = image.getPixelRGBA(x, y) >>> 24;
                    if (a > 10 && a < 240) { translucent[i] = true; break; }
                }
            textures[i] = ResourceLocation.fromNamespaceAndPath(PhysicalistLibrary.MOD_ID, "soft/" + next++);
            manager.register(textures[i], new DynamicTexture(image));
        }
        return true;
    }

    /** Triangles that span pieces that broke apart are not drawn: the tear. */
    private void tears(int[] components, int version) {
        if (hidden != null && version == hiddenVersion && components == hiddenComponents) return;
        hiddenVersion = version;
        hiddenComponents = components;
        hidden = new boolean[model.meshes.size()][];
        int[] an = structure.anchorNodes;
        for (int k = 0; k < model.meshes.size(); k++) {
            int[] idx = model.meshes.get(k).indices();
            boolean[] h = new boolean[idx.length / 3];
            if (components != null && components.length == structure.nodes) {
                int base = structure.meshStart[k];
                for (int t = 0; t < h.length; t++) {
                    int c = components[an[(base + idx[t * 3]) * 3]];
                    for (int corner = 0; corner < 3 && !h[t]; corner++) {
                        int v = base + idx[t * 3 + corner];
                        for (int j = 0; j < 3; j++) if (components[an[v * 3 + j]] != c) { h[t] = true; break; }
                    }
                }
            }
            hidden[k] = h;
        }
    }

    /** Pose at render time: node positions (relative to the pose origin), control deflections, rotor speed. */
    record Pose(double[] nodes, float[] controls, float[] rotors) {}

    /** Interpolated pose at partial tick relative to (ox, oy, oz), or null before the first pose arrived. */
    static Pose pose(SoftBodyEntity e, SoftStructure s, float partial, double ox, double oy, double oz) {
        var timeline = e.timeline();
        double[] nodes = new double[s.nodes * 3];
        double t = timeline.time(partial);
        if (!timeline.sample(t, nodes)) {
            double[] cur = e.nodes();
            if (cur == null || cur.length != nodes.length) return null;
            System.arraycopy(cur, 0, nodes, 0, nodes.length);
        }
        for (int i = 0; i < nodes.length; i += 3) { nodes[i] -= ox; nodes[i + 1] -= oy; nodes[i + 2] -= oz; }
        var pair = timeline.around(t);
        float[] controls = new float[SoftStructure.CHANNELS], rotors = e.rotors();
        if (pair != null) {
            double f = timeline.fraction(t);
            float[] a = pair[0].controls(), b = pair[1].controls();
            for (int c = 0; c < controls.length && c < a.length && c < b.length; c++) controls[c] = (float) (a[c] + (b[c] - a[c]) * f);
            float[] ra = pair[0].rotors(), rb = pair[1].rotors();
            if (ra.length == rb.length) {
                rotors = ra.clone();
                for (int i = 0; i < rotors.length; i++) rotors[i] = (float) (ra[i] + (rb[i] - ra[i]) * f);
            }
        }
        return new Pose(nodes, controls, rotors);
    }

    boolean render(SoftBodyEntity e, float partial, double ox, double oy, double oz, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!ready()) return false;
        var p = pose(e, structure, partial, ox, oy, oz);
        if (p == null) return false;
        double[] nodes = p.nodes();
        tears(e.components(), e.version());
        // The propeller: its real speed is far too fast to see; show it turning slowly and blurred above that.
        long now = System.nanoTime();
        double seconds = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        double omega = p.rotors().length >= 2 ? p.rotors()[1] : 0;
        propAngle = (propAngle + Math.min(omega, SHOWN_SPIN) * seconds) % (2 * Math.PI);
        var last = pose.last();
        int[] an = structure.anchorNodes;
        float[] ac = structure.anchorCoords;
        double[] rest = structure.rest;
        double[] q = new double[3];
        for (int k = 0; k < model.meshes.size(); k++) {
            var mesh = model.meshes.get(k);
            int material = mesh.material();
            var type = translucent[material] ? RenderType.entityTranslucent(textures[material]) : RenderType.entityCutoutNoCull(textures[material]);
            var vc = buffers.getBuffer(type);
            int color = model.materials.get(material).color();
            int n = mesh.vertices(), base = structure.meshStart[k];
            if (scratch.length < n * 3) scratch = new double[n * 3];
            int ch = structure.meshChannel[k];
            double angle = 0;
            double[] centre = null, axis = null;
            if (ch >= 0 && Math.abs(p.controls()[ch]) > 1e-4) {
                angle = p.controls()[ch];
                double[] h = structure.hinge[k];
                centre = new double[]{h[0], h[1], h[2]};
                axis = new double[]{h[3], h[4], h[5]};
            } else if (structure.meshSpins[k] && structure.rotorHub != null) {
                angle = propAngle;
                centre = structure.rotorHub;
                axis = structure.rotorAxis;
            } else if (wheelHub[k] != null && n > 0) {
                angle = roll(e, k, nodes, ox, oy, oz);
                centre = wheelHub[k];
                axis = WHEEL_AXLE;
            }
            for (int v = 0; v < n; v++) {
                int a = base + v;
                float u = ac[a * 3], w = ac[a * 3 + 1], z = ac[a * 3 + 2];
                if (axis != null) {
                    // Turned in the rest shape, then pinned to the same nodes as the unturned vertex.
                    double[] r = SoftStructure.rotate(new double[]{placed[k][v * 3] - centre[0], placed[k][v * 3 + 1] - centre[1], placed[k][v * 3 + 2] - centre[2]}, axis, angle);
                    int i0 = an[a * 3], i1 = an[a * 3 + 1], i2 = an[a * 3 + 2];
                    double[] c = SoftStructure.frameCoordinates(at(rest, i0), at(rest, i1), at(rest, i2), new double[]{r[0] + centre[0], r[1] + centre[1], r[2] + centre[2]});
                    u = (float) c[0]; w = (float) c[1]; z = (float) c[2];
                }
                SoftStructure.pointInFrame(nodes, an[a * 3], an[a * 3 + 1], an[a * 3 + 2], u, w, z, q);
                scratch[v * 3] = q[0]; scratch[v * 3 + 1] = q[1]; scratch[v * 3 + 2] = q[2];
            }
            triangles(vc, last, mesh.indices(), mesh.uvs(), hidden[k], color, light);
        }
        boolean propeller = false;
        for (boolean spin : structure.meshSpins) propeller |= spin;
        if (propeller && omega > SHOWN_SPIN && structure.rotorHub != null && structure.rotorFrame() != null) disc(nodes, omega, buffers, last, light);
        return true;
    }

    /** Wheels turn about the model's sideways axis (models face +Z with +Y up). */
    private static final double[] WHEEL_AXLE = {1, 0, 0};

    /**
     * How far wheel mesh k has turned: the distance its hub rolled forward over the ground since the last frame, over
     * its radius. Off the ground (in the air, a jump) it keeps the angle it had.
     */
    private double roll(SoftBodyEntity e, int k, double[] nodes, double ox, double oy, double oz) {
        int a = structure.meshStart[k];
        int[] an = structure.anchorNodes;
        int i0 = an[a * 3], i1 = an[a * 3 + 1], i2 = an[a * 3 + 2];
        double[] rest = structure.rest, hub = wheelHub[k];
        double[] c = SoftStructure.frameCoordinates(at(rest, i0), at(rest, i1), at(rest, i2), hub);
        double[] ahead = SoftStructure.frameCoordinates(at(rest, i0), at(rest, i1), at(rest, i2), new double[]{hub[0], hub[1], hub[2] + 1});
        double[] h = new double[3], f = new double[3];
        SoftStructure.pointInFrame(nodes, i0, i1, i2, (float) c[0], (float) c[1], (float) c[2], h);
        SoftStructure.pointInFrame(nodes, i0, i1, i2, (float) ahead[0], (float) ahead[1], (float) ahead[2], f);
        double[] world = {h[0] + ox, h[1] + oy, h[2] + oz};
        double[] last = wheelLast[k];
        wheelLast[k] = world;
        if (last == null) return wheelAngle[k];
        double dx = world[0] - last[0], dy = world[1] - last[1], dz = world[2] - last[2];
        if (dx * dx + dy * dy + dz * dz > 25) return wheelAngle[k];
        double r = wheelRadius[k];
        var below = net.minecraft.core.BlockPos.containing(world[0], world[1] - r - 0.35, world[2]);
        if (e.level().getBlockState(below).getCollisionShape(e.level(), below).isEmpty()) return wheelAngle[k];
        double fx = f[0] - h[0], fy = f[1] - h[1], fz = f[2] - h[2], fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-9) return wheelAngle[k];
        double along = (dx * fx + dy * fy + dz * fz) / fl;
        wheelAngle[k] = (wheelAngle[k] + along / r) % (2 * Math.PI);
        return wheelAngle[k];
    }

    private void triangles(VertexConsumer vc, PoseStack.Pose last, int[] idx, float[] uv, boolean[] h, int color, int light) {
        for (int t = 0; t < idx.length / 3; t++) {
            if (h[t]) continue;
            int i0 = idx[t * 3], i1 = idx[t * 3 + 1], i2 = idx[t * 3 + 2];
            double ax = scratch[i0 * 3], ay = scratch[i0 * 3 + 1], az = scratch[i0 * 3 + 2];
            double ux = scratch[i1 * 3] - ax, uy = scratch[i1 * 3 + 1] - ay, uz = scratch[i1 * 3 + 2] - az;
            double vx = scratch[i2 * 3] - ax, vy = scratch[i2 * 3 + 1] - ay, vz = scratch[i2 * 3 + 2] - az;
            float nx = (float) (uy * vz - uz * vy), ny = (float) (uz * vx - ux * vz), nz = (float) (ux * vy - uy * vx);
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1e-12f) continue;
            nx /= l; ny /= l; nz /= l;
            int[] corners = {i0, i1, i2, i2};
            for (int c : corners)
                vc.addVertex(last, (float) scratch[c * 3], (float) scratch[c * 3 + 1], (float) scratch[c * 3 + 2]).setColor(color)
                        .setUv(uv[c * 2], uv[c * 2 + 1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
        }
    }

    /** The blur of a fast propeller: a faint disc, more solid the faster it turns. */
    private void disc(double[] nodes, double omega, MultiBufferSource buffers, PoseStack.Pose last, int light) {
        int[] f = structure.rotorFrame();
        double[] rest = structure.rest, hub = structure.rotorHub, axis = structure.rotorAxis;
        double radius = structure.rotors[9];
        double[] u = Math.abs(axis[1]) < 0.9 ? SoftStructure.unit(SoftStructure.crossOf(axis, new double[]{0, 1, 0})) : SoftStructure.unit(SoftStructure.crossOf(axis, new double[]{1, 0, 0}));
        double[] v = SoftStructure.crossOf(axis, u);
        int segments = 28;
        double[] pts = new double[(segments + 1) * 3], q = new double[3];
        for (int s = 0; s <= segments; s++) {
            double a = 2 * Math.PI * s / segments, r = s == segments ? 0 : radius;
            double[] p = {hub[0] + (u[0] * Math.cos(a) + v[0] * Math.sin(a)) * r, hub[1] + (u[1] * Math.cos(a) + v[1] * Math.sin(a)) * r, hub[2] + (u[2] * Math.cos(a) + v[2] * Math.sin(a)) * r};
            double[] c = SoftStructure.frameCoordinates(at(rest, f[0]), at(rest, f[1]), at(rest, f[2]), p);
            SoftStructure.pointInFrame(nodes, f[0], f[1], f[2], (float) c[0], (float) c[1], (float) c[2], q);
            System.arraycopy(q, 0, pts, s * 3, 3);
        }
        int alpha = (int) Math.clamp((omega - SHOWN_SPIN) / 220 * 110, 0, 110);
        int color = alpha << 24 | 0x2A2622;
        var vc = buffers.getBuffer(RenderType.entityTranslucent(white));
        int c0 = segments * 3;
        for (int s = 0; s < segments; s++) {
            int a = s * 3, b = (s + 1) % segments * 3;
            for (int idx : new int[]{c0, a, b, b})
                vc.addVertex(last, (float) pts[idx], (float) pts[idx + 1], (float) pts[idx + 2]).setColor(color).setUv(0, 0)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, (float) axis[0], (float) axis[1], (float) axis[2]);
        }
    }

    private static double[] at(double[] p, int i) { return new double[]{p[i * 3], p[i * 3 + 1], p[i * 3 + 2]}; }
}
