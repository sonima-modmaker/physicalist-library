package dev.physicalist.soft;

import java.util.*;

/**
 * Turns a model into a node-and-beam structure, the way a BeamNG jbeam is laid out by hand, but automatically:
 * <ul>
 * <li>the surface is sampled and clustered into nodes about h apart; opposite faces of a thin part (a wing's top and
 * bottom skin) stay separate nodes, so the part has thickness and resists bending;</li>
 * <li>each node gets beams to its nearest neighbours (crossing beams give shear and bending stiffness); beams between
 * different named parts are weaker, so a wing tears off before the fuselage tears apart;</li>
 * <li>every node carries a patch of the hull for aerodynamics;</li>
 * <li>every render vertex is pinned to three nearby nodes, so the drawn mesh bends with the structure;</li>
 * <li>parts named "door" hang on two hinge nodes, swing up to 90 degrees outwards and are held shut by a latch.</li>
 * </ul>
 * The same model always gives the same structure, so the server (physics) and clients (drawing) agree on it.
 */
public final class SoftStructure {
    public static final int SUBSTEPS = 100;
    /** How many times the plain yield distance a node of landing gear / a wing springs back from. */
    static final double GEAR_YIELD = 8, WING_YIELD = 100;
    /** Propeller engine power per kilogram of the whole aircraft (W/kg). */
    static final double PROP_POWER = 380;
    /** How much of a plate's aerodynamic force an aircraft's fuselage skin gets. */
    static final double FUSELAGE_AERO = 0.4;
    /** Strain at which a wing's brittle root snaps (below it the root is purely elastic). */
    static final double WING_ROOT_BREAK = 0.08;
    public static final double DT = 0.05 / SUBSTEPS;
    private static final int MAX_NODES = 800, NEIGHBOURS = 16;
    public static final int BEAM_STRIDE = 9, AERO_STRIDE = 11, NODE_STRIDE = 14, ROTOR_STRIDE = 24, WHEEL_STRIDE = 28, SKIN_STRIDE = 7;
    /** Control channels: elevator (+ = nose down), left aileron, right aileron (+ on channel 1, - on 2 = roll right), rudder (+ = nose right). */
    public static final int ELEVATOR = 0, AILERON_LEFT = 1, AILERON_RIGHT = 2, RUDDER = 3, CHANNELS = 4;
    /** An engine part weighs this much more per square metre than the rest of the hull. */
    static final double ENGINE_MASS = 9;
    /** Shape-matching stiffness per substep and how far a node may be pushed before the dent stays. */
    public static final double SHAPE_STIFFNESS = 0.03, YIELD = 0.05;

    /** Model-space rest positions (metres, Y up, centred on x/z, standing on y=0). */
    public final double[] rest;
    public final double nodeMass, radius, spacing, scale, height;
    public final double[] beams, aero;
    /** Per render vertex in mesh order: three node indices and the coordinates in their frame. */
    public final int[] anchorNodes;
    public final float[] anchorCoords;
    public final int[] meshStart;
    public final List<Door> doors;
    public final int nodes;
    /** Shape-matching group of each node: 0 the body (and door stops), 1.. a door, 1000 + door for its hinge nodes. */
    public final int[] nodeGroup;
    /** False for hinge and door-stop nodes, which never touch anything. */
    public final boolean[] solid;
    /** Mass of every node (an engine is heavy). */
    public final double[] mass;
    /** How far each node may be pushed before it stays bent, in multiples of {@link #YIELD} (landing gear is springy). */
    public final double[] nodeYield;
    /** Shape groups once the body has been knocked hard (per part); {@link #nodeGroup} while whole. */
    public final int[] crashGroup;
    /** Per node: -1, or the wheel frame {b, c} and the axle in the (node, b, c) frame, see {@link #nodeSpec}. */
    public final double[] wheels;
    /** Native rotor descriptions (empty without a propeller) and, for drawing, the hub and axis in model space. */
    public final double[] rotors;
    public final double[] rotorHub, rotorAxis;
    /** Per mesh: control channel or -1; hinge point and axis (6 values) for those; whether it spins with the rotor. */
    public final int[] meshChannel;
    public final double[][] hinge;
    public final boolean[] meshSpins;
    /** Where the pilot sits (pinned like a render vertex), or null without a seat. */
    public final int[] seatNodes;
    public final float[] seatCoords;
    public final double[] seatRest;
    /** Model forward (+Z) pinned to the seat nodes, to know where the vehicle points. */
    public final float[] forwardCoords;
    /** Per node, {@link #SKIN_STRIDE} values: frame nodes b, c (-1: no plate), the outward normal in the (node, b, c) frame, plate half size toward b and across. */
    public final double[] skin;

    /**
     * A door: its nodes, the node on its free edge, the latch beam that holds it shut, the hinge beams (a door whose
     * hinge beams all broke has fallen off), the hinge nodes and the side it opens to (+1/-1 around the hinge axis).
     */
    public record Door(String name, int[] nodes, int free, int latch, int hold, int[] hinges, int hingeA, int hingeB, double turn, double length) {}

    private record Extras(double[] mass, double[] nodeYield, int[] crashGroups, double[] wheels, double[] rotors, double[] rotorHub, double[] rotorAxis, int[] meshChannel, double[][] hinge,
                          boolean[] meshSpins, int[] seatNodes, float[] seatCoords, double[] seatRest, float[] forwardCoords, double[] skin) {}

    private SoftStructure(double[] rest, double nodeMass, double radius, double spacing, double scale, double height, double[] beams, double[] aero,
                          int[] anchorNodes, float[] anchorCoords, int[] meshStart, List<Door> doors, int[] nodeGroup, boolean[] solid, Extras x) {
        this.nodeGroup = nodeGroup;
        this.solid = solid;
        this.mass = x.mass; this.nodeYield = x.nodeYield; this.crashGroup = x.crashGroups; this.wheels = x.wheels; this.rotors = x.rotors; this.rotorHub = x.rotorHub; this.rotorAxis = x.rotorAxis;
        this.meshChannel = x.meshChannel; this.hinge = x.hinge; this.meshSpins = x.meshSpins;
        this.seatNodes = x.seatNodes; this.seatCoords = x.seatCoords; this.seatRest = x.seatRest; this.forwardCoords = x.forwardCoords; this.skin = x.skin;
        this.rest = rest; this.nodeMass = nodeMass; this.radius = radius; this.spacing = spacing; this.scale = scale; this.height = height;
        this.beams = beams; this.aero = aero; this.anchorNodes = anchorNodes; this.anchorCoords = anchorCoords; this.meshStart = meshStart;
        this.doors = doors; this.nodes = rest.length / 3;
    }

    public double totalMass() { double m = 0; for (double v : mass) m += v; return m; }
    public int beamCount() { return beams.length / BEAM_STRIDE; }
    public boolean hasSeat() { return seatNodes != null; }
    public int rotorCount() { return rotors.length / ROTOR_STRIDE; }
    /** The three nodes the (first) rotor is pinned to, or null. */
    public int[] rotorFrame() { return rotors.length < ROTOR_STRIDE ? null : new int[]{(int) rotors[0], (int) rotors[1], (int) rotors[2]}; }
    public static double[] crossOf(double[] a, double[] b) { return cross(a, b); }
    public static double[] unit(double[] a) { double l = len(a); return l < 1e-12 ? a : scaleV(a, 1 / l); }

    /** Native node description: {x,y,z,mass,group,solid,friction,wheel b,c,axle} for node positions p (flat xyz). */
    public double[] nodeSpec(double[] p) {
        double[] spec = new double[nodes * NODE_STRIDE];
        for (int i = 0; i < nodes; i++) {
            int o = i * NODE_STRIDE;
            spec[o] = p[i * 3]; spec[o + 1] = p[i * 3 + 1]; spec[o + 2] = p[i * 3 + 2];
            spec[o + 3] = mass[i]; spec[o + 4] = nodeGroup[i]; spec[o + 5] = solid[i] ? 1 : 0; spec[o + 6] = -1;
            System.arraycopy(wheels, i * 5, spec, o + 7, 5);
            spec[o + 12] = nodeYield[i];
            spec[o + 13] = crashGroup[i];
        }
        return spec;
    }

    /** Kinds of mesh by name. */
    static boolean spins(String name) { String n = name.toLowerCase(Locale.ROOT); return n.contains("propeller") || n.contains("spinner"); }
    static boolean engine(String name) { return name.toLowerCase(Locale.ROOT).contains("engine"); }
    static boolean wheel(String name) { return name.toLowerCase(Locale.ROOT).contains("wheel"); }
    static boolean gear(String name) { String n = name.toLowerCase(Locale.ROOT); return n.contains("wheel") || n.contains("gear") || n.contains("strut"); }
    /** Wings, tailplanes and their control surfaces: they flex under load and spring back. */
    static boolean airfoil(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String t : new String[]{"wing", "aileron", "flap", "stabilizer", "stabiliser", "elevator", "rudder", "tailplane", "fin"})
            if (n.contains(t) && !(t.equals("fin") && (n.contains("finish") || n.contains("define")))) return true;
        return false;
    }
    static boolean seat(String name) { String n = name.toLowerCase(Locale.ROOT); return n.contains("seat") && !n.contains("back"); }

    /** The model's triangles in a coarse grid, for "is there skin along this ray" tests. */
    private static final class Shell {
        private final List<double[]> tris = new ArrayList<>();
        private final Map<Long, List<Integer>> cells = new HashMap<>();
        private final double cell;

        Shell(double[][] verts, SoftModel model, boolean[] skip, double h) {
            cell = Math.max(0.25, h);
            for (int k = 0; k < verts.length; k++) {
                if (skip[k]) continue;
                int[] idx = model.meshes.get(k).indices();
                double[] v = verts[k];
                for (int t = 0; t + 2 < idx.length; t += 3) {
                    int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
                    if (c + 2 >= v.length || a + 2 >= v.length || b + 2 >= v.length) continue;
                    double[] tri = {v[a], v[a + 1], v[a + 2], v[b], v[b + 1], v[b + 2], v[c], v[c + 1], v[c + 2]};
                    int id = tris.size();
                    tris.add(tri);
                    int[] lo = new int[3], hi = new int[3];
                    for (int ax = 0; ax < 3; ax++) {
                        double mn = Math.min(tri[ax], Math.min(tri[3 + ax], tri[6 + ax])), mx = Math.max(tri[ax], Math.max(tri[3 + ax], tri[6 + ax]));
                        lo[ax] = (int) Math.floor(mn / cell); hi[ax] = (int) Math.floor(mx / cell);
                    }
                    for (int x = lo[0]; x <= hi[0]; x++) for (int y = lo[1]; y <= hi[1]; y++) for (int z = lo[2]; z <= hi[2]; z++)
                        cells.computeIfAbsent(key(x, y, z), q -> new ArrayList<>()).add(id);
                }
            }
        }

        private static long key(int x, int y, int z) { return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }

        boolean enclosed(double[] p, double[] n, double reach) {
            return hits(p, n, reach) && hits(p, new double[]{-n[0], -n[1], -n[2]}, reach);
        }

        /** Whether a ray from just off p along d meets a triangle within reach (the patch's own skin skipped). */
        private boolean hits(double[] p, double[] d, double reach) {
            double start = 0.08;
            Set<Integer> tried = new HashSet<>();
            for (double s = 0; s <= reach + cell; s += cell * 0.5) {
                int cx = (int) Math.floor((p[0] + d[0] * s) / cell), cy = (int) Math.floor((p[1] + d[1] * s) / cell), cz = (int) Math.floor((p[2] + d[2] * s) / cell);
                var list = cells.get(key(cx, cy, cz));
                if (list == null) continue;
                for (int id : list) {
                    if (!tried.add(id)) continue;
                    double t = ray(p, d, tris.get(id));
                    if (t > start && t < reach) return true;
                }
            }
            return false;
        }

        /** Möller–Trumbore: distance along d to the triangle, or -1. */
        private static double ray(double[] o, double[] d, double[] t) {
            double e1x = t[3] - t[0], e1y = t[4] - t[1], e1z = t[5] - t[2], e2x = t[6] - t[0], e2y = t[7] - t[1], e2z = t[8] - t[2];
            double px = d[1] * e2z - d[2] * e2y, py = d[2] * e2x - d[0] * e2z, pz = d[0] * e2y - d[1] * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-12) return -1;
            double inv = 1 / det, sx = o[0] - t[0], sy = o[1] - t[1], sz = o[2] - t[2];
            double u = (sx * px + sy * py + sz * pz) * inv;
            if (u < 0 || u > 1) return -1;
            double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            double v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv;
            if (v < 0 || u + v > 1) return -1;
            return (e2x * qx + e2y * qy + e2z * qz) * inv;
        }
    }

    private static final class Acc {
        double x, y, z, w, sx, sy, sz;
        final int group;
        final Map<Integer, Double> parts = new TreeMap<>(), meshes = new TreeMap<>();
        Acc(int group) { this.group = group; }
        void merge(Acc o) {
            x += o.x; y += o.y; z += o.z; w += o.w; sx += o.sx; sy += o.sy; sz += o.sz;
            o.parts.forEach((k, v) -> parts.merge(k, v, Double::sum));
            o.meshes.forEach((k, v) -> meshes.merge(k, v, Double::sum));
        }
        int mesh() { int best = 0; double bw = -1; for (var e : meshes.entrySet()) if (e.getValue() > bw) { bw = e.getValue(); best = e.getKey(); } return best; }
        double share(boolean[] of) { double s = 0; for (var e : meshes.entrySet()) if (of[e.getKey()]) s += e.getValue(); return s / Math.max(w, 1e-12); }
        double[] p() { return new double[]{x / w, y / w, z / w}; }
        double[] n() { double l = Math.sqrt(sx * sx + sy * sy + sz * sz); return l < 1e-12 ? new double[3] : new double[]{sx / l, sy / l, sz / l}; }
        int part() { int best = 0; double bw = -1; for (var e : parts.entrySet()) if (e.getValue() > bw) { bw = e.getValue(); best = e.getKey(); } return best; }
    }

    /**
     * How model space maps to structure space: {scale, x, y, z} with structure = (model - (x, y, z)) * scale (centred on
     * x/z, standing on y = 0, an optional unit fix for models made in millimetres or far too small).
     */
    public static double[] placement(SoftModel model, double userScale) {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (var m : model.meshes) for (int i = 0; i < m.positions().length; i++) { lo[i % 3] = Math.min(lo[i % 3], m.positions()[i]); hi[i % 3] = Math.max(hi[i % 3], m.positions()[i]); }
        double extent = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        double unit = extent > 64 ? 12 / extent : extent < 0.25 ? 2 / Math.max(extent, 1e-6) : 1;
        return new double[]{unit * Math.clamp(userScale, 0.05, 20), (lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2};
    }

    /** Model space to structure space: optional unit fix, user scale, centred on x/z, standing on y=0. */
    public static double[][] placed(SoftModel model, double userScale) {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (var m : model.meshes) for (int i = 0; i < m.positions().length; i++) { lo[i % 3] = Math.min(lo[i % 3], m.positions()[i]); hi[i % 3] = Math.max(hi[i % 3], m.positions()[i]); }
        double extent = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        // Models in millimetres or centimetres would be a kilometre across; dust-sized ones invisible.
        double unit = extent > 64 ? 12 / extent : extent < 0.25 ? 2 / Math.max(extent, 1e-6) : 1;
        double s = unit * Math.clamp(userScale, 0.05, 20);
        double cx = (lo[0] + hi[0]) / 2, cz = (lo[2] + hi[2]) / 2;
        double[][] out = new double[model.meshes.size()][];
        for (int k = 0; k < out.length; k++) {
            float[] p = model.meshes.get(k).positions();
            double[] q = new double[p.length];
            for (int i = 0; i < p.length; i += 3) { q[i] = (p[i] - cx) * s; q[i + 1] = (p[i + 1] - lo[1]) * s; q[i + 2] = (p[i + 2] - cz) * s; }
            out[k] = q;
        }
        // The last row carries {scale, height}.
        return withScale(out, s, (hi[1] - lo[1]) * s);
    }
    private static double[][] withScale(double[][] meshes, double s, double height) {
        double[][] out = Arrays.copyOf(meshes, meshes.length + 1);
        out[meshes.length] = new double[]{s, height};
        return out;
    }

    /** Path segment that names a door ("Car/door_left/glass" -> "door_left"), or null. */
    static String door(String name) {
        for (String part : name.split("[/|:]")) if (part.toLowerCase(Locale.ROOT).contains("door")) return part;
        return null;
    }

    public static SoftStructure build(SoftModel model, double userScale) { return build(model, userScale, 0); }

    /** As {@link #build(SoftModel, double)}, the node masses scaled so the whole weighs totalMass kg (when > 0). */
    public static SoftStructure build(SoftModel model, double userScale, double totalMass) {
        double[][] placedAll = placed(model, userScale);
        double scale = placedAll[placedAll.length - 1][0], height = placedAll[placedAll.length - 1][1];
        double[][] verts = Arrays.copyOf(placedAll, placedAll.length - 1);
        int meshes = model.meshes.size();
        // Groups: 0 is the body, 1.. one per door; parts: first path segment, for weaker joints between them.
        Map<String, Integer> doorIds = new LinkedHashMap<>(), partIds = new LinkedHashMap<>();
        int[] group = new int[meshes], part = new int[meshes];
        double area = 0;
        double[] doorArea = new double[meshes + 1];
        boolean[] spinning = new boolean[meshes], heavy = new boolean[meshes], rolling = new boolean[meshes], springy = new boolean[meshes], flexing = new boolean[meshes];
        for (int k = 0; k < meshes; k++) {
            String name = model.meshes.get(k).name();
            spinning[k] = spins(name);
            heavy[k] = engine(name);
            rolling[k] = wheel(name);
            springy[k] = gear(name);
            flexing[k] = airfoil(name);
        }
        // Only an aircraft's wings spring: a car's "wing" is a fender, its "flap" a fuel lid.
        boolean aircraft = false;
        for (int k = 0; k < meshes; k++) {
            String n = model.meshes.get(k).name().toLowerCase(Locale.ROOT);
            aircraft |= spinning[k] || n.contains("aileron") || n.contains("elevator") || n.contains("rudder") || n.contains("stabilizer") || n.contains("stabiliser");
        }
        if (!aircraft) Arrays.fill(flexing, false);
        boolean allSpin = true;
        for (boolean s : spinning) allSpin &= s;
        if (allSpin) Arrays.fill(spinning, false);
        for (int k = 0; k < meshes; k++) {
            String name = model.meshes.get(k).name();
            if (spinning[k]) { group[k] = 0; part[k] = partIds.computeIfAbsent(name.split("[/|]")[0], x -> partIds.size()); continue; }
            String d = door(name);
            group[k] = d == null ? 0 : doorIds.computeIfAbsent(d, x -> doorIds.size() + 1);
            part[k] = partIds.computeIfAbsent(name.split("[/|]")[0], x -> partIds.size());
            int[] idx = model.meshes.get(k).indices();
            for (int t = 0; t < idx.length; t += 3) {
                double a = triangleArea(verts[k], idx[t], idx[t + 1], idx[t + 2]);
                area += a;
                doorArea[group[k]] += a;
            }
        }
        area = Math.max(area, 1e-4);
        double h = Math.clamp(Math.sqrt(area / 450), 0.08, 2.0);
        // A door too small for a few nodes of its own is just part of the body.
        boolean regroup = false;
        for (int g = 1; g <= doorIds.size(); g++) if (doorArea[g] < 5 * h * h) regroup = true;
        if (regroup) for (int k = 0; k < meshes; k++) if (group[k] > 0 && doorArea[group[k]] < 5 * h * h) group[k] = 0;
        List<Acc> nodes = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            nodes = cluster(model, verts, group, part, spinning, h);
            if (nodes.size() > MAX_NODES) h *= 1.2;
            else if (nodes.size() < 12 && attempt < 7) h /= 1.5;
            else break;
        }
        int n0 = nodes.size();
        List<double[]> pos = new ArrayList<>();
        List<Integer> nodeGroup = new ArrayList<>(), nodePart = new ArrayList<>();
        List<double[]> normals = new ArrayList<>();
        List<Double> patch = new ArrayList<>();
        for (var a : nodes) {
            pos.add(a.p()); nodeGroup.add(a.group); nodePart.add(a.part()); normals.add(a.n());
            patch.add(Math.sqrt(a.sx * a.sx + a.sy * a.sy + a.sz * a.sz));
        }
        double nodeMass = Math.clamp(area * 10 / n0, 0.05, 2000);
        double radius = Math.clamp(h * 0.3, 0.03, 0.35);

        // Beams: nearest neighbours within reach, inside one group.
        List<int[]> beamPairs = new ArrayList<>();
        List<Double> beamStrength = new ArrayList<>();
        Set<Long> have = new HashSet<>();
        var grid = new Grid(h * 1.8);
        for (int i = 0; i < n0; i++) grid.add(pos.get(i), i);
        for (int i = 0; i < n0; i++) {
            List<int[]> near = new ArrayList<>();
            double[] p = pos.get(i);
            for (int j : grid.near(p, h * 1.8)) {
                if (j == i || !nodeGroup.get(j).equals(nodeGroup.get(i))) continue;
                double d = dist(p, pos.get(j));
                if (d <= h * 1.8) near.add(new int[]{j, (int) (d * 1e6)});
            }
            near.sort(Comparator.comparingInt(x -> x[1]));
            for (int k = 0; k < Math.min(NEIGHBOURS, near.size()); k++) addBeam(i, near.get(k)[0], have, beamPairs, beamStrength, nodePart.get(i).equals(nodePart.get(near.get(k)[0])) ? 0.45 : 0.12);
        }
        // Every group must hang together: bridge loose pieces (wheels a little apart from the body) to the largest.
        for (int g = 0; g <= doorIds.size(); g++) connect(g, pos, nodeGroup, beamPairs, beamStrength, have, n0);

        // Doors.
        List<Door> doors = new ArrayList<>();
        List<double[]> extraBeams = new ArrayList<>();
        for (var entry : doorIds.entrySet()) {
            int g = entry.getValue();
            List<Integer> members = new ArrayList<>(), body = new ArrayList<>();
            for (int i = 0; i < n0; i++) if (nodeGroup.get(i) == g) members.add(i); else if (nodeGroup.get(i) == 0) body.add(i);
            if (members.size() < 3 || body.size() < 4) continue;
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9}, centre = new double[3];
            int count = 0;
            for (int k = 0; k < meshes; k++) {
                if (group[k] != g) continue;
                for (int i = 0; i < verts[k].length; i += 3) for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], verts[k][i + a]); hi[a] = Math.max(hi[a], verts[k][i + a]); }
            }
            for (int a = 0; a < 3; a++) centre[a] = (lo[a] + hi[a]) / 2;
            double[] ext = {hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]};
            int thin = ext[0] <= ext[1] && ext[0] <= ext[2] ? 0 : ext[1] <= ext[2] ? 1 : 2;
            int[] others = thin == 0 ? new int[]{1, 2} : thin == 1 ? new int[]{0, 2} : new int[]{0, 1};
            // Hinge axis: vertical when the door stands upright (car doors), else along the door's longer side.
            int axis = others[0] == 1 || others[1] == 1 ? 1 : ext[others[0]] >= ext[others[1]] ? others[0] : others[1];
            int free = others[0] == axis ? others[1] : others[0];
            double[] middle = {0, height / 2, 0};
            // Hinged on the edge farther from the middle of the vehicle (a car's front door hinges at its front).
            double hingeSide = centre[free] - middle[free] >= 0 ? hi[free] : lo[free];
            double freeSide = centre[free] - middle[free] >= 0 ? lo[free] : hi[free];
            double out = centre[thin] - middle[thin] >= 0 ? 1 : -1;
            double[] h1 = centre.clone(), h2 = centre.clone(), f0 = centre.clone();
            h1[free] = h2[free] = hingeSide;
            h1[axis] = lo[axis] + ext[axis] * 0.15;
            h2[axis] = hi[axis] - ext[axis] * 0.15;
            f0[free] = freeSide;
            int nf = nearest(members, pos, f0);
            double[] fp = pos.get(nf);
            double[] axisDir = new double[3];
            axisDir[axis] = 1;
            double[] foot = h1.clone();
            foot[axis] = fp[axis];
            double length = dist(fp, foot);
            if (length < h * 0.5) continue;
            double[] outward = new double[3];
            outward[thin] = out;
            // Which way round the axis is "outwards": the free edge must move along +outward.
            double turn = dot(cross(axisDir, sub(fp, foot)), outward) >= 0 ? 1 : -1;
            double[] p90 = add(foot, rotate(sub(fp, foot), axisDir, turn * Math.PI / 2));
            double[] inner = sub(foot, scaleV(outward, length));
            // Hinge nodes belong to the shape of both the body and the door (1000 + door); the stop nodes to the body.
            int ha = pos.size(); pos.add(h1); nodeGroup.add(1000 + g); nodePart.add(-1); normals.add(new double[3]); patch.add(0.);
            int hb = pos.size(); pos.add(h2); nodeGroup.add(1000 + g); nodePart.add(-1); normals.add(new double[3]); patch.add(0.);
            int b1 = pos.size(); pos.add(fp.clone()); nodeGroup.add(0); nodePart.add(-1); normals.add(new double[3]); patch.add(0.);
            int b2 = pos.size(); pos.add(inner); nodeGroup.add(0); nodePart.add(-1); normals.add(new double[3]); patch.add(0.);
            // Where the free edge is when fully open: a check strap holds the door there.
            int b3 = pos.size(); pos.add(p90); nodeGroup.add(0); nodePart.add(-1); normals.add(new double[3]); patch.add(0.);
            List<Integer> hinges = new ArrayList<>();
            for (int hinge : new int[]{ha, hb}) {
                for (int j : nearestK(body, pos, pos.get(hinge), 4)) { hinges.add(beamPairs.size()); addBeam(hinge, j, have, beamPairs, beamStrength, 0.12); }
                for (int j : nearestK(members, pos, pos.get(hinge), 4)) { hinges.add(beamPairs.size()); addBeam(hinge, j, have, beamPairs, beamStrength, 0.12); }
            }
            addBeam(ha, hb, have, beamPairs, beamStrength, 0.45);
            for (int anchor : new int[]{b1, b2, b3}) for (int j : nearestK(body, pos, pos.get(anchor), 4)) addBeam(anchor, j, have, beamPairs, beamStrength, 0.45);
            // Stops: no further than 90 degrees out, never into the body; the latch holds it shut.
            extraBeams.add(new double[]{nf, b1, 1, 0, dist(p90, fp)});
            extraBeams.add(new double[]{nf, b2, 1, dist(fp, inner) * 0.97, 1e6});
            extraBeams.add(new double[]{nf, b1, 2, 0, 0});
            extraBeams.add(new double[]{nf, b3, 2, 0, 0});
            int[] memberArray = members.stream().mapToInt(Integer::intValue).toArray();
            doors.add(new Door(entry.getKey(), memberArray, nf, -1, -1, hinges.stream().mapToInt(Integer::intValue).toArray(), ha, hb, turn, length));
        }
        int n = pos.size();
        double[] mass = new double[n];
        for (int i = 0; i < n; i++) mass[i] = i < n0 ? nodeMass * (1 + (ENGINE_MASS - 1) * nodes.get(i).share(heavy)) : nodeMass;
        if (totalMass > 0) {
            double sum = 0;
            for (double m : mass) sum += m;
            double f = totalMass / Math.max(sum, 1e-9);
            for (int i = 0; i < n; i++) mass[i] *= f;
            nodeMass *= f;
        }
        // Landing gear carries the whole weight on a few nodes: it springs instead of bending at the first bump.
        double[] nodeYield = new double[n];
        boolean[] gearNode = new boolean[n], wingNode = new boolean[n];
        for (int i = 0; i < n0; i++) {
            gearNode[i] = nodes.get(i).share(springy) > 0.3;
            wingNode[i] = !gearNode[i] && nodes.get(i).share(flexing) > 0.3;
        }
        // Wings and tailplanes never keep a bend: they flex under load and always spring back. Only a crash-size load
        // breaks their brittle root and tears them off whole.
        for (int i = 0; i < n; i++) nodeYield[i] = gearNode[i] ? GEAR_YIELD : wingNode[i] ? WING_YIELD : 1;
        // Stiffness: as stiff as an explicit 2000 Hz step allows for the lighter end's mass and number of beams.
        int[] degree = new int[n];
        for (int[] b : beamPairs) { degree[b[0]]++; degree[b[1]]++; }
        for (double[] b : extraBeams) { degree[(int) b[0]]++; degree[(int) b[1]]++; }
        int total = beamPairs.size() + extraBeams.size();
        double[] beams = new double[total * BEAM_STRIDE];
        for (int i = 0; i < beamPairs.size(); i++) {
            int a = beamPairs.get(i)[0], b = beamPairs.get(i)[1];
            double m = Math.min(mass[a], mass[b]);
            double k = stiffness(m, Math.max(degree[a], degree[b]));
            // Plastic strain limit: gear 0.08; inside a wing none at all (purely elastic); a wing's root brittle (negative:
            // elastic up to that strain, then it snaps); the rest of the body crumples past 0.012.
            double deform = gearNode[a] || gearNode[b] ? 0.08 : wingNode[a] && wingNode[b] ? 0 : wingNode[a] || wingNode[b] ? -WING_ROOT_BREAK : 0.012;
            put(beams, i, a, b, k, damping(k, m, Math.max(degree[a], degree[b])), deform, beamStrength.get(i), 0, 0, 0);
        }
        List<Door> finished = new ArrayList<>();
        for (int i = 0; i < extraBeams.size(); i++) {
            double[] e = extraBeams.get(i);
            int a = (int) e[0], b = (int) e[1], kind = (int) e[2], deg = Math.max(degree[a], degree[b]);
            double m = Math.min(mass[a], mass[b]);
            // The latch is only switched on once the door is back in its frame, so it can be stiff.
            double k = stiffness(m, deg) * (kind == 2 ? 0.6 : 0.5);
            put(beams, beamPairs.size() + i, a, b, k, damping(k, m, deg) * (kind == 2 ? 1.5 : 1), 0, 0, kind, e[3], e[4]);
        }
        // Control surfaces: by name; the hinge runs along the span (horizontal ones) or upright (a rudder), at the
        // surface's front edge.
        int[] meshChannel = new int[meshes];
        double[][] hinge = new double[meshes][];
        for (int k = 0; k < meshes; k++) {
            meshChannel[k] = -1;
            if (spinning[k] || verts[k].length == 0) continue;
            String name = model.meshes.get(k).name().toLowerCase(Locale.ROOT);
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            for (int i = 0; i < verts[k].length; i += 3) for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], verts[k][i + a]); hi[a] = Math.max(hi[a], verts[k][i + a]); }
            int ch = name.contains("elevator") ? ELEVATOR : name.contains("aileron") ? (lo[0] + hi[0] > 0 ? AILERON_LEFT : AILERON_RIGHT) : name.contains("rudder") ? RUDDER : -1;
            if (ch < 0) continue;
            meshChannel[k] = ch;
            boolean upright = ch == RUDDER;
            hinge[k] = new double[]{(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, hi[2], upright ? 0 : -1, upright ? 1 : 0, 0};
        }
        for (int d = 0; d < doors.size(); d++) {
            var door = doors.get(d);
            finished.add(new Door(door.name(), door.nodes(), door.free(), beamPairs.size() + d * 4 + 2, beamPairs.size() + d * 4 + 3, door.hinges(), door.hingeA(), door.hingeB(), door.turn(), door.length()));
        }

        // Aerodynamic patches: each node's hull area, on a triangle of neighbours lying in its surface.
        List<List<Integer>> neighbours = new ArrayList<>();
        for (int i = 0; i < n; i++) neighbours.add(new ArrayList<>());
        for (int[] b : beamPairs) { neighbours.get(b[0]).add(b[1]); neighbours.get(b[1]).add(b[0]); }
        List<double[]> aero = new ArrayList<>();
        // Surfaces shut in by the model's own skin (a cabin's insides, seats, double walls) never meet the air: a
        // patch with skin both ways along its normal, close by, gets no aerodynamics.
        var shell = new Shell(verts, model, spinning, h);
        for (int i = 0; i < n0; i++) {
            double a = patch.get(i);
            double[] nn = normals.get(i);
            if (a < 1e-9) continue;
            if (shell.enclosed(pos.get(i), nn, 2.5)) continue;
            var list = neighbours.get(i);
            int bi = -1, ci = -1;
            double best = 0.3;
            double[] p = pos.get(i), bestCross = null;
            for (int x = 0; x < list.size(); x++) for (int y = x + 1; y < list.size(); y++) {
                double[] e1 = sub(pos.get(list.get(x)), p), e2 = sub(pos.get(list.get(y)), p), c = cross(e1, e2);
                double q = Math.abs(dot(c, nn)) / (len(e1) * len(e2) + 1e-12);
                if (q > best) { best = q; bi = list.get(x); ci = list.get(y); bestCross = c; }
            }
            if (bi < 0) continue;
            // The patch normal is stored in the frame of its triangle, so it turns and bends with the structure.
            double[] f = frameCoordinates(p, pos.get(bi), pos.get(ci), add(p, nn));
            int mesh = nodes.get(i).mesh(), ch = meshChannel[mesh];
            double[] t = {0, 0, 0};
            if (ch >= 0) {
                // Deflecting turns the normal about the hinge: n cos d + (hinge x n) sin d.
                double[] axis = {hinge[mesh][3], hinge[mesh][4], hinge[mesh][5]};
                t = frameCoordinates(p, pos.get(bi), pos.get(ci), add(p, cross(axis, nn)));
            }
            // An aircraft's fuselage is a slender body: its skin makes far less lift (and drag) than a plate of the same
            // area; only wings, tailplanes and control surfaces act as full plates.
            double bodyShare = aircraft && ch < 0 && !flexing[mesh] ? FUSELAGE_AERO : 1;
            aero.add(new double[]{i, bi, ci, Math.min(100, a / len(bestCross)) * bodyShare, f[0], f[1], f[2], ch, t[0], t[1], t[2]});
        }
        // Walkable skin: every surface node carries a small plate along the hull, pinned to two neighbours so it turns
        // and bends with the structure. Overlapping plates make a closed floor players can stand on.
        double[] skin = new double[n * SKIN_STRIDE];
        Arrays.fill(skin, -1);
        for (int i = 0; i < n0; i++) {
            double[] nn = normals.get(i);
            if (len(nn) < 1e-9) continue;
            var list = neighbours.get(i);
            nn = fitNormal(pos, i, list, unit(nn), h);
            int bi = -1, ci = -1;
            double best = 0.2;
            double[] p = pos.get(i);
            for (int x = 0; x < list.size(); x++) for (int y = x + 1; y < list.size(); y++) {
                double[] e1 = sub(pos.get(list.get(x)), p), e2 = sub(pos.get(list.get(y)), p), c = cross(e1, e2);
                double q = Math.abs(dot(c, nn)) / (len(e1) * len(e2) + 1e-12);
                if (q > best) { best = q; bi = list.get(x); ci = list.get(y); }
            }
            if (bi < 0) continue;
            double[] f = frameCoordinates(p, pos.get(bi), pos.get(ci), add(p, unit(nn)));
            // The plate's axes, as the game rebuilds them: t toward b in the plate's plane, u across.
            double[] n1 = unit(nn), tb = sub(pos.get(bi), p);
            tb = unit(sub(tb, scaleV(n1, dot(tb, n1))));
            double[] ub = cross(n1, tb);
            // Each way it reaches about 0.65 of the way to the farthest close neighbour that way, so plates overlap into
            // a closed floor but stop at an edge: at a wing's leading edge the plate stands upright and must not stick
            // up above the wing.
            double reachT = 0, reachU = 0;
            for (int j : list) {
                double[] d = sub(pos.get(j), p);
                // Only its own part's skin: a thin strut under a wing must not grow a plate as wide as the wing's.
                if (len(d) > 1.6 * h || j >= n0 || nodes.get(j).mesh() != nodes.get(i).mesh() || Math.abs(dot(d, n1)) > 0.5 * len(d)) continue;
                reachT = Math.max(reachT, Math.abs(dot(d, tb)));
                reachU = Math.max(reachU, Math.abs(dot(d, ub)));
            }
            skin[i * SKIN_STRIDE] = bi; skin[i * SKIN_STRIDE + 1] = ci;
            skin[i * SKIN_STRIDE + 2] = f[0]; skin[i * SKIN_STRIDE + 3] = f[1]; skin[i * SKIN_STRIDE + 4] = f[2];
            skin[i * SKIN_STRIDE + 5] = Math.clamp(reachT * 0.65, 0.04, 1.2);
            skin[i * SKIN_STRIDE + 6] = Math.clamp(reachU * 0.65, 0.04, 1.2);
        }
        // Wheels: their nodes roll along the ground about the wheel's axle (its thinnest side).
        double[] wheels = new double[n * 5];
        for (int i = 0; i < n; i++) wheels[i * 5] = wheels[i * 5 + 1] = -1;
        double[][] meshAxle = new double[meshes][];
        for (int k = 0; k < meshes; k++) {
            if (!rolling[k] || verts[k].length == 0) continue;
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            for (int i = 0; i < verts[k].length; i += 3) for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], verts[k][i + a]); hi[a] = Math.max(hi[a], verts[k][i + a]); }
            int thin = hi[0] - lo[0] <= hi[1] - lo[1] && hi[0] - lo[0] <= hi[2] - lo[2] ? 0 : hi[1] - lo[1] <= hi[2] - lo[2] ? 1 : 2;
            meshAxle[k] = new double[3];
            meshAxle[k][thin] = 1;
        }
        for (var patchEntry : aero) {
            int i = (int) patchEntry[0];
            double[] axle = meshAxle[nodes.get(i).mesh()];
            if (axle == null || nodes.get(i).share(rolling) < 0.5) continue;
            int bi = (int) patchEntry[1], ci = (int) patchEntry[2];
            double[] c = frameCoordinates(pos.get(i), pos.get(bi), pos.get(ci), add(pos.get(i), axle));
            wheels[i * 5] = bi; wheels[i * 5 + 1] = ci; wheels[i * 5 + 2] = c[0]; wheels[i * 5 + 3] = c[1]; wheels[i * 5 + 4] = c[2];
        }
        double[] aeroArray = new double[aero.size() * AERO_STRIDE];
        for (int i = 0; i < aero.size(); i++) System.arraycopy(aero.get(i), 0, aeroArray, i * AERO_STRIDE, AERO_STRIDE);

        // Render anchors.
        int vertices = 0;
        int[] meshStart = new int[meshes + 1];
        for (int k = 0; k < meshes; k++) { meshStart[k] = vertices; vertices += verts[k].length / 3; }
        meshStart[meshes] = vertices;
        int[] anchorNodes = new int[vertices * 3];
        float[] anchorCoords = new float[vertices * 3];
        Map<Integer, Grid> groupGrids = new HashMap<>();
        Map<Integer, List<Integer>> groupMembers = new HashMap<>();
        for (int i = 0; i < n0; i++) groupMembers.computeIfAbsent(nodeGroup.get(i), x -> new ArrayList<>()).add(i);
        for (var e : groupMembers.entrySet()) { var gg = new Grid(h); for (int i : e.getValue()) gg.add(pos.get(i), i); groupGrids.put(e.getKey(), gg); }
        var bodyGrid = groupGrids.get(0);
        var bodyMembers = groupMembers.getOrDefault(0, List.of());

        // The rotor: hub in the middle of the spinning parts, axis through the disc's thin side pointing away from
        // the airframe (forwards for a tractor propeller), pinned to body nodes near the hub.
        double[] rotorArray = new double[0], rotorHub = null, rotorAxis = null;
        int[] rotorFrame = null;
        double allMass = 0;
        for (double m : mass) allMass += m;
        if (!bodyMembers.isEmpty()) {
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            boolean any = false;
            for (int k = 0; k < meshes; k++) if (spinning[k]) for (int i = 0; i < verts[k].length; i += 3) {
                any = true;
                for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], verts[k][i + a]); hi[a] = Math.max(hi[a], verts[k][i + a]); }
            }
            if (any) {
                double[] hub = {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
                // The disc's thin side, when it clearly has one; a two-blade propeller with its spinner does not, so
                // it turns about the model's forward axis (+Z).
                double[] ext = {hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]};
                Integer[] order = {0, 1, 2};
                Arrays.sort(order, Comparator.comparingDouble(a -> ext[a]));
                int thin = ext[order[0]] < 0.5 * ext[order[1]] ? order[0] : 2;
                double[] mid = new double[3];
                for (int i = 0; i < n0; i++) for (int a = 0; a < 3; a++) mid[a] += pos.get(i)[a] / n0;
                double[] axis = new double[3];
                axis[thin] = hub[thin] - mid[thin] >= 0 ? 1 : -1;
                double discRadius = 0;
                for (int k = 0; k < meshes; k++) if (spinning[k]) for (int i = 0; i < verts[k].length; i += 3) {
                    double[] d = sub(new double[]{verts[k][i], verts[k][i + 1], verts[k][i + 2]}, hub);
                    discRadius = Math.max(discRadius, len(sub(d, scaleV(axis, dot(d, axis)))));
                }
                if (discRadius > 0.05) {
                    rotorFrame = pin(hub, bodyGrid, bodyMembers, pos, h);
                    double[] fh = frameCoordinates(pos.get(rotorFrame[0]), pos.get(rotorFrame[1]), pos.get(rotorFrame[2]), hub);
                    double[] fa = frameCoordinates(pos.get(rotorFrame[0]), pos.get(rotorFrame[1]), pos.get(rotorFrame[2]), add(pos.get(rotorFrame[0]), axis));
                    List<Integer> hubNodes = nearestK(bodyMembers, pos, hub, 7);
                    double s = discRadius / 0.95;
                    rotorArray = new double[ROTOR_STRIDE];
                    // Coarser pitch and a higher rev limit than before: the engine's power reaches the air in flight
                    // too, instead of the propeller hitting its rev limit with power to spare.
                    double[] r = {rotorFrame[0], rotorFrame[1], rotorFrame[2], fh[0], fh[1], fh[2], fa[0], fa[1], fa[2], discRadius, 2, 0.15 * s, 2.3 * s,
                            2.5 * s * s, PROP_POWER * allMass, 320 / discRadius, hubNodes.size()};
                    System.arraycopy(r, 0, rotorArray, 0, r.length);
                    for (int i = 0; i < hubNodes.size(); i++) rotorArray[17 + i] = hubNodes.get(i);
                    rotorHub = hub;
                    rotorAxis = axis;
                }
            }
        }
        // The seat: on top of the seat part, in the middle.
        int[] seatNodes = null;
        float[] seatCoords = null, forwardCoords = null;
        double[] seatRest = null;
        for (int k = 0; k < meshes && seatNodes == null; k++) {
            if (!seat(model.meshes.get(k).name()) || verts[k].length == 0 || bodyMembers.size() < 3) continue;
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            for (int i = 0; i < verts[k].length; i += 3) for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], verts[k][i + a]); hi[a] = Math.max(hi[a], verts[k][i + a]); }
            seatRest = new double[]{(lo[0] + hi[0]) / 2, hi[1], (lo[2] + hi[2]) / 2};
            seatNodes = pin(seatRest, bodyGrid, bodyMembers, pos, h);
            double[] a = pos.get(seatNodes[0]), b = pos.get(seatNodes[1]), c = pos.get(seatNodes[2]);
            double[] sc = frameCoordinates(a, b, c, seatRest), fc = frameCoordinates(a, b, c, add(a, new double[]{0, 0, 1}));
            seatCoords = new float[]{(float) sc[0], (float) sc[1], (float) sc[2]};
            forwardCoords = new float[]{(float) fc[0], (float) fc[1], (float) fc[2]};
        }

        for (int k = 0; k < meshes; k++) {
            int g = groupMembers.containsKey(group[k]) && groupMembers.get(group[k]).size() >= 3 ? group[k] : 0;
            var gg = groupGrids.get(g);
            var members = groupMembers.get(g);
            for (int v = 0; v < verts[k].length / 3; v++) {
                double[] p = {verts[k][v * 3], verts[k][v * 3 + 1], verts[k][v * 3 + 2]};
                // A propeller turns as one piece about the hub: every vertex hangs on the rotor's own frame.
                int[] tri = spinning[k] && rotorFrame != null ? rotorFrame : pin(p, gg, members, pos, h);
                int at = meshStart[k] + v;
                anchorNodes[at * 3] = tri[0]; anchorNodes[at * 3 + 1] = tri[1]; anchorNodes[at * 3 + 2] = tri[2];
                double[] c = frameCoordinates(pos.get(tri[0]), pos.get(tri[1]), pos.get(tri[2]), p);
                anchorCoords[at * 3] = (float) c[0]; anchorCoords[at * 3 + 1] = (float) c[1]; anchorCoords[at * 3 + 2] = (float) c[2];
            }
        }
        double[] rest = new double[n * 3];
        for (int i = 0; i < n; i++) System.arraycopy(pos.get(i), 0, rest, i * 3, 3);
        // Shape-matching clusters: one per named part of the body (so a wing can tear off the fuselage once the beams
        // joining them snap), parts too small for a shape of their own join the nearest larger one; one per door.
        // An aircraft's airframe keeps one shape while it is whole (a wing must not flap at its root in flight) and
        // falls back to one per part after its first hard knock.
        int split = partIds.size() > 900 ? 1 : partIds.size();
        // Whole, wings and tailplanes keep the fuselage's shape, so ordinary flight loads never bend them at the root
        // (held by beams alone they sagged and snapped in level flight). After the first hard knock (a crash) every part
        // keeps its own shape, so a wing can be torn off.
        int[] crashGroups = shapeGroups(split, n, n0, nodeGroup, nodePart, gearNode, new boolean[n], pos);
        int[] groups = shapeGroups(split, n, n0, nodeGroup, nodePart, gearNode, wingNode, pos);
        boolean[] solid = new boolean[n];
        for (int i = 0; i < n0; i++) solid[i] = true;
        boolean[] meshSpins = rotorFrame != null ? spinning : new boolean[meshes];
        var extras = new Extras(mass, nodeYield, crashGroups, wheels, rotorArray, rotorHub, rotorAxis, meshChannel, hinge, meshSpins, seatNodes, seatCoords, seatRest, forwardCoords, skin);
        return new SoftStructure(rest, nodeMass, radius, h, scale, height, beams, aeroArray, anchorNodes, anchorCoords, meshStart, finished, groups, solid, extras);
    }

    /** Shape-matching group of every node when the body is split into `parts` part shapes (see the native side). */
    private static int[] shapeGroups(int parts, int n, int n0, List<Integer> nodeGroup, List<Integer> nodePart, boolean[] gearNode, boolean[] wingNode, List<double[]> pos) {
        int[] partCount = new int[parts];
        for (int i = 0; i < n0; i++) if (nodeGroup.get(i) == 0) partCount[Math.min(nodePart.get(i), parts - 1)]++;
        List<Integer> bigBody = new ArrayList<>();
        for (int i = 0; i < n0; i++) if (nodeGroup.get(i) == 0 && !gearNode[i] && !wingNode[i] && partCount[Math.min(nodePart.get(i), parts - 1)] >= 8) bigBody.add(i);
        // A model that is nothing but wing (a glider plank) keeps them as its own parts.
        boolean wingsJoin = !bigBody.isEmpty();
        if (!wingsJoin) for (int i = 0; i < n0; i++) if (nodeGroup.get(i) == 0 && !gearNode[i] && partCount[Math.min(nodePart.get(i), parts - 1)] >= 8) bigBody.add(i);
        int[] groups = new int[n];
        for (int i = 0; i < n; i++) {
            int g = nodeGroup.get(i);
            if (i < n0 && g > 0) { groups[i] = parts + g; continue; }
            int own = i < n0 ? Math.min(nodePart.get(i), parts - 1) : -1;
            // Wheels and their legs keep the shape of the wing or fuselage they hang from (or a wheel would spin on its leg).
            if (own < 0 || partCount[own] < 8 || i < n0 && (gearNode[i] || wingsJoin && wingNode[i])) own = bigBody.isEmpty() ? 0 : Math.min(nodePart.get(nearest(bigBody, pos, pos.get(i))), parts - 1);
            groups[i] = g >= 1000 ? 1000 * (own + 1) + parts + (g - 1000) : own;
        }
        return groups;
    }
    /**
     * The skin's own normal at node i: a least-squares plane through its close neighbours on the same skin (the node's
     * clustered normal can lean 40 degrees near an edge, and a plate that leans stands proud of the hull). Neighbours on
     * the opposite skin (a wing's underside) are about as far off the plane as they are away, and are left out.
     */
    private static double[] fitNormal(List<double[]> pos, int i, List<Integer> list, double[] n, double h) {
        double[] p = pos.get(i);
        for (int pass = 0; pass < 2; pass++) {
            double[] t = unit(Math.abs(n[1]) < 0.9 ? cross(n, new double[]{0, 1, 0}) : cross(n, new double[]{1, 0, 0})), u = cross(n, t);
            double stt = 0, stu = 0, suu = 0, ste = 0, sue = 0;
            int count = 0;
            for (int j : list) {
                double[] d = sub(pos.get(j), p);
                double l = len(d), e = dot(d, n);
                if (l > 1.6 * h || l < 1e-9 || Math.abs(e) > 0.5 * l) continue;
                double a = dot(d, t), b = dot(d, u);
                stt += a * a; stu += a * b; suu += b * b; ste += a * e; sue += b * e;
                count++;
            }
            double det = stt * suu - stu * stu;
            if (count < 3 || Math.abs(det) < 1e-12) return n;
            double gt = (ste * suu - sue * stu) / det, gu = (sue * stt - ste * stu) / det;
            double[] fitted = unit(sub(n, add(scaleV(t, gt), scaleV(u, gu))));
            if (dot(fitted, n) < 0.5) return n;
            n = fitted;
        }
        return n;
    }

    /** Three nodes near p that make a well-shaped frame to pin p to. */
    private static int[] pin(double[] p, Grid gg, List<Integer> members, List<double[]> pos, double h) {
        int[] cand = gg.nearest(p, 10, members, pos);
        int a = cand[0];
        int bestB = cand.length > 1 ? cand[1] : a, bestC = cand.length > 2 ? cand[2] : a;
        double best = -1;
        for (int x = 1; x < cand.length; x++) for (int y = x + 1; y < cand.length; y++) {
            double[] e1 = sub(pos.get(cand[x]), pos.get(a)), e2 = sub(pos.get(cand[y]), pos.get(a));
            double q = len(cross(e1, e2)) / (dot(e1, e1) + dot(e2, e2) + 1e-12);
            double score = Math.min(q, 0.35) / (1 + (dist(p, pos.get(cand[x])) + dist(p, pos.get(cand[y]))) / h);
            if (score > best) { best = score; bestB = cand[x]; bestC = cand[y]; }
        }
        return new int[]{a, bestB, bestC};
    }

    /**
     * A road wheel description for the native solver (see NativeSoft.add0): the strut's top at mount, pointing up,
     * the wheel rolling along forward (all in structure space), pinned to the body nodes nearest the mount.
     */
    public double[] roadWheel(double[] mount, double[] up, double[] forward, double radius, double restLength, double travel, double spring, double damper,
                              double inertia, double gripAlong, double gripAcross, double massShare) {
        Set<Integer> doorNodes = new HashSet<>();
        for (var d : doors) for (int i : d.nodes()) doorNodes.add(i);
        List<Integer> body = new ArrayList<>();
        List<double[]> pos = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            pos.add(new double[]{rest[i * 3], rest[i * 3 + 1], rest[i * 3 + 2]});
            if (solid[i] && !doorNodes.contains(i)) body.add(i);
        }
        if (body.size() < 3) throw new IllegalArgumentException("no body to hang a wheel on");
        var grid = new Grid(spacing);
        for (int i : body) grid.add(pos.get(i), i);
        int[] f = pin(mount, grid, body, pos, spacing);
        double[] a = pos.get(f[0]), b = pos.get(f[1]), c = pos.get(f[2]);
        double[] m = frameCoordinates(a, b, c, mount), u = frameCoordinates(a, b, c, add(a, unit(up))), w = frameCoordinates(a, b, c, add(a, unit(forward)));
        List<Integer> near = nearestK(body, pos, mount, 6);
        double[] out = new double[WHEEL_STRIDE];
        double[] head = {f[0], f[1], f[2], m[0], m[1], m[2], u[0], u[1], u[2], w[0], w[1], w[2], radius, restLength, travel, spring, damper, inertia, gripAlong, gripAcross, massShare, near.size()};
        System.arraycopy(head, 0, out, 0, head.length);
        for (int i = 0; i < near.size(); i++) out[22 + i] = near.get(i);
        return out;
    }

    /** World position of the seat for node positions p, or null. */
    public double[] seat(double[] p) {
        if (seatNodes == null) return null;
        double[] out = new double[3];
        pointInFrame(p, seatNodes[0], seatNodes[1], seatNodes[2], seatCoords[0], seatCoords[1], seatCoords[2], out);
        return out;
    }

    /** Unit forward direction of the vehicle (model +Z) at the seat for node positions p, or null. */
    public double[] forward(double[] p) {
        if (seatNodes == null) return null;
        double[] out = new double[3];
        pointInFrame(p, seatNodes[0], seatNodes[1], seatNodes[2], forwardCoords[0], forwardCoords[1], forwardCoords[2], out);
        int a = seatNodes[0];
        double x = out[0] - p[a * 3], y = out[1] - p[a * 3 + 1], z = out[2] - p[a * 3 + 2], l = Math.sqrt(x * x + y * y + z * z);
        return l < 1e-9 ? null : new double[]{x / l, y / l, z / l};
    }

    private static double stiffness(double mass, int degree) { return 0.45 * mass / (DT * DT * Math.max(1, degree)); }
    private static double damping(double k, double mass, int degree) { return Math.min(0.2 * 2 * Math.sqrt(k * mass / 2), 0.35 * mass / (DT * Math.max(1, degree))); }

    private static void put(double[] beams, int i, int a, int b, double k, double c, double deform, double strength, int kind, double min, double max) {
        int o = i * BEAM_STRIDE;
        beams[o] = a; beams[o + 1] = b; beams[o + 2] = k; beams[o + 3] = c; beams[o + 4] = deform; beams[o + 5] = strength;
        beams[o + 6] = kind; beams[o + 7] = min; beams[o + 8] = max;
    }

    private static void addBeam(int a, int b, Set<Long> have, List<int[]> pairs, List<Double> strength, double s) {
        if (a == b) return;
        long key = (long) Math.min(a, b) << 32 | Math.max(a, b);
        if (!have.add(key)) return;
        pairs.add(new int[]{Math.min(a, b), Math.max(a, b)});
        strength.add(s);
    }

    private static void connect(int g, List<double[]> pos, List<Integer> nodeGroup, List<int[]> pairs, List<Double> strength, Set<Long> have, int n0) {
        for (int round = 0; round < 64; round++) {
            int[] parent = new int[n0];
            for (int i = 0; i < n0; i++) parent[i] = i;
            for (int[] b : pairs) if (b[0] < n0 && b[1] < n0) union(parent, b[0], b[1]);
            Map<Integer, List<Integer>> parts = new TreeMap<>();
            for (int i = 0; i < n0; i++) if (nodeGroup.get(i) == g) parts.computeIfAbsent(find(parent, i), x -> new ArrayList<>()).add(i);
            if (parts.size() <= 1) return;
            List<Integer> largest = parts.values().stream().max(Comparator.comparingInt(List::size)).get();
            for (var piece : parts.values()) {
                if (piece == largest) continue;
                // Three shortest links from this piece to the main one.
                List<double[]> links = new ArrayList<>();
                for (int i : piece) { int j = nearest(largest, pos, pos.get(i)); links.add(new double[]{dist(pos.get(i), pos.get(j)), i, j}); }
                links.sort(Comparator.comparingDouble(x -> x[0]));
                Set<Integer> used = new HashSet<>();
                for (var l : links) {
                    if (used.size() >= 3) break;
                    if (!used.add((int) l[2])) continue;
                    addBeam((int) l[1], (int) l[2], have, pairs, strength, 0.3);
                }
            }
        }
    }
    private static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }
    private static void union(int[] p, int a, int b) { a = find(p, a); b = find(p, b); if (a != b) p[Math.max(a, b)] = Math.min(a, b); }

    private static List<Acc> cluster(SoftModel model, double[][] verts, int[] group, int[] part, boolean[] skip, double h) {
        Map<Long, Acc> cells = new LinkedHashMap<>();
        for (int k = 0; k < verts.length; k++) {
            if (skip[k]) continue;
            int[] idx = model.meshes.get(k).indices();
            double[] v = verts[k];
            for (int t = 0; t < idx.length; t += 3) {
                double[] a = at(v, idx[t]), b = at(v, idx[t + 1]), c = at(v, idx[t + 2]);
                double[] cr = cross(sub(b, a), sub(c, a));
                double area2 = len(cr);
                if (area2 < 1e-12) continue;
                double[] nrm = scaleV(cr, 1 / area2);
                int axis = Math.abs(nrm[0]) >= Math.abs(nrm[1]) && Math.abs(nrm[0]) >= Math.abs(nrm[2]) ? 0 : Math.abs(nrm[1]) >= Math.abs(nrm[2]) ? 1 : 2;
                int code = axis * 2 + (nrm[axis] >= 0 ? 0 : 1);
                double edge = Math.max(dist(a, b), Math.max(dist(b, c), dist(c, a)));
                int m = (int) Math.clamp(Math.ceil(edge / (h * 0.5)), 1, 48);
                int samples = (m + 1) * (m + 2) / 2;
                double w = area2 / 2 / samples;
                for (int i = 0; i <= m; i++) for (int j = 0; i + j <= m; j++) {
                    double u = (double) i / m, s = (double) j / m;
                    double x = a[0] + (b[0] - a[0]) * u + (c[0] - a[0]) * s, y = a[1] + (b[1] - a[1]) * u + (c[1] - a[1]) * s, z = a[2] + (b[2] - a[2]) * u + (c[2] - a[2]) * s;
                    long key = cellKey((int) Math.floor(x / h), (int) Math.floor(y / h), (int) Math.floor(z / h)) * 8 + code;
                    key = key * 64 + group[k];
                    int g = group[k];
                    var acc = cells.computeIfAbsent(key, q -> new Acc(g));
                    acc.x += x * w; acc.y += y * w; acc.z += z * w; acc.w += w;
                    acc.sx += nrm[0] * w; acc.sy += nrm[1] * w; acc.sz += nrm[2] * w;
                    acc.parts.merge(part[k], w, Double::sum);
                    acc.meshes.merge(k, w, Double::sum);
                }
            }
        }
        // Merge clusters that sit almost on top of each other and face the same way (not a wing's two skins).
        List<Acc> out = new ArrayList<>();
        var grid = new Grid(h * 0.35);
        for (var acc : cells.values()) {
            if (acc.w <= 0) continue;
            double[] p = acc.p(), nrm = acc.n();
            Acc into = null;
            for (int j : grid.near(p, h * 0.35)) {
                var o = out.get(j);
                if (o.group == acc.group && dist(o.p(), p) < h * 0.35 && dot(o.n(), nrm) > 0.3) { into = o; break; }
            }
            if (into != null) into.merge(acc);
            else { grid.add(p, out.size()); out.add(acc); }
        }
        return out;
    }

    static long cellKey(int x, int y, int z) { return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (z & 0xFFFF); }

    /** Uniform hash grid of node indices. */
    static final class Grid {
        final double cell;
        final Map<Long, List<Integer>> map = new HashMap<>();
        final List<double[]> points = new ArrayList<>();
        Grid(double cell) { this.cell = cell; }
        void add(double[] p, int i) {
            map.computeIfAbsent(cellKey((int) Math.floor(p[0] / cell), (int) Math.floor(p[1] / cell), (int) Math.floor(p[2] / cell)), k -> new ArrayList<>()).add(i);
        }
        List<Integer> near(double[] p, double r) {
            List<Integer> out = new ArrayList<>();
            int x0 = (int) Math.floor((p[0] - r) / cell), x1 = (int) Math.floor((p[0] + r) / cell);
            int y0 = (int) Math.floor((p[1] - r) / cell), y1 = (int) Math.floor((p[1] + r) / cell);
            int z0 = (int) Math.floor((p[2] - r) / cell), z1 = (int) Math.floor((p[2] + r) / cell);
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                var l = map.get(cellKey(x, y, z));
                if (l != null) out.addAll(l);
            }
            return out;
        }
        /** Up to k nearest of the given members, searching outwards; falls back to all members. */
        int[] nearest(double[] p, int k, List<Integer> members, List<double[]> pos) {
            List<Integer> found = List.of();
            for (int ring = 1; ring <= 6; ring++) {
                found = near(p, cell * ring);
                if (found.size() >= k) break;
            }
            if (found.size() < Math.min(k, members.size())) found = members;
            List<Integer> sorted = new ArrayList<>(found);
            sorted.sort(Comparator.comparingDouble(i -> dist(pos.get(i), p)));
            int[] out = new int[Math.min(k, sorted.size())];
            for (int i = 0; i < out.length; i++) out[i] = sorted.get(i);
            return out;
        }
    }

    private static int nearest(List<Integer> among, List<double[]> pos, double[] p) {
        int best = among.get(0);
        double bd = Double.MAX_VALUE;
        for (int i : among) { double d = dist(pos.get(i), p); if (d < bd) { bd = d; best = i; } }
        return best;
    }
    private static List<Integer> nearestK(List<Integer> among, List<double[]> pos, double[] p, int k) {
        List<Integer> sorted = new ArrayList<>(among);
        sorted.sort(Comparator.comparingDouble(i -> dist(pos.get(i), p)));
        return sorted.subList(0, Math.min(k, sorted.size()));
    }

    /** Coordinates (u, v, w) of p in the frame a, b-a, c-a, normal; see {@link #pointInFrame}. */
    public static double[] frameCoordinates(double[] a, double[] b, double[] c, double[] p) {
        double[] e1 = sub(b, a), e2 = sub(c, a), cr = cross(e1, e2);
        double l = len(cr);
        if (l < 1e-12) return new double[]{0, 0, 0};
        double[] e3 = scaleV(cr, 1 / Math.sqrt(l));
        double[] d = sub(p, a);
        double det = dot(e1, cross(e2, e3));
        if (Math.abs(det) < 1e-14) return new double[]{0, 0, 0};
        return new double[]{dot(d, cross(e2, e3)) / det, dot(e1, cross(d, e3)) / det, dot(e1, cross(e2, d)) / det};
    }

    /** Inverse of {@link #frameCoordinates}: where a pinned vertex is for the current node positions (flat xyz array). */
    public static void pointInFrame(double[] nodes, int a, int b, int c, float u, float v, float w, double[] out) {
        double ax = nodes[a * 3], ay = nodes[a * 3 + 1], az = nodes[a * 3 + 2];
        double e1x = nodes[b * 3] - ax, e1y = nodes[b * 3 + 1] - ay, e1z = nodes[b * 3 + 2] - az;
        double e2x = nodes[c * 3] - ax, e2y = nodes[c * 3 + 1] - ay, e2z = nodes[c * 3 + 2] - az;
        double cx = e1y * e2z - e1z * e2y, cy = e1z * e2x - e1x * e2z, cz = e1x * e2y - e1y * e2x;
        double l = Math.sqrt(cx * cx + cy * cy + cz * cz), s = l < 1e-12 ? 0 : 1 / Math.sqrt(l);
        out[0] = ax + u * e1x + v * e2x + w * cx * s;
        out[1] = ay + u * e1y + v * e2y + w * cy * s;
        out[2] = az + u * e1z + v * e2z + w * cz * s;
    }

    private static double triangleArea(double[] v, int a, int b, int c) { return len(cross(sub(at(v, b), at(v, a)), sub(at(v, c), at(v, a)))) / 2; }
    private static double[] at(double[] v, int i) { return new double[]{v[i * 3], v[i * 3 + 1], v[i * 3 + 2]}; }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    static double[] scaleV(double[] a, double s) { return new double[]{a[0] * s, a[1] * s, a[2] * s}; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double len(double[] a) { return Math.sqrt(dot(a, a)); }
    static double dist(double[] a, double[] b) { return len(sub(a, b)); }
    /** Rodrigues rotation of v about a unit axis. */
    public static double[] rotate(double[] v, double[] axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return add(add(scaleV(v, c), scaleV(cross(axis, v), s)), scaleV(axis, dot(axis, v) * (1 - c)));
    }
}
