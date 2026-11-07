package dev.physicalist.soft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Binary FBX (6.1-7.7): meshes with their node transforms, UV sets, per-polygon materials, diffuse colours and
 * textures, embedded or next to the file. The unit (centimetres in most exports) and the up axis are applied.
 */
final class FbxReader {
    record Node(String name, List<Object> props, List<Node> children) {
        Node child(String n) { for (var c : children) if (c.name.equals(n)) return c; return null; }
        List<Node> all(String n) { var out = new ArrayList<Node>(); for (var c : children) if (c.name.equals(n)) out.add(c); return out; }
        Object prop(int i) { return i < props.size() ? props.get(i) : null; }
        long id() { return props.isEmpty() ? 0 : ((Number) props.get(0)).longValue(); }
    }

    private final ByteBuffer b;
    private final boolean wide;

    private FbxReader(byte[] data, int version) { b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); wide = version >= 7500; }

    static SoftModel read(SoftModelLoader.Source source, String file, byte[] data) throws IOException {
        if (data.length < 27 || !new String(data, 0, 18, StandardCharsets.US_ASCII).equals("Kaydara FBX Binary"))
            throw new IOException("Текстовый (ASCII) FBX не поддерживается: экспортируйте FBX как Binary или в GLB");
        int version = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(23);
        var reader = new FbxReader(data, version);
        List<Node> top = new ArrayList<>();
        int at = 27;
        reader.b.position(at);
        while (reader.b.remaining() > (reader.wide ? 25 : 13)) {
            Node n = reader.node();
            if (n == null) break;
            top.add(n);
        }
        return new Scene(top, source, SoftModelLoader.folderOf(file)).build();
    }

    private Node node() throws IOException {
        long end = wide ? b.getLong() : b.getInt() & 0xFFFFFFFFL;
        long count = wide ? b.getLong() : b.getInt() & 0xFFFFFFFFL;
        if (wide) b.getLong(); else b.getInt();
        int nameLength = b.get() & 255;
        if (end == 0) return null;
        if (end > b.capacity() || count > 1_000_000) throw new IOException("Повреждённый FBX");
        byte[] name = new byte[nameLength];
        b.get(name);
        List<Object> props = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) props.add(property());
        List<Node> children = new ArrayList<>();
        while (b.position() < end) {
            Node c = node();
            if (c == null) break;
            children.add(c);
        }
        b.position((int) end);
        return new Node(new String(name, StandardCharsets.UTF_8), props, children);
    }

    private Object property() throws IOException {
        char type = (char) b.get();
        return switch (type) {
            case 'Y' -> (long) b.getShort();
            case 'C' -> (long) b.get();
            case 'I' -> (long) b.getInt();
            case 'L' -> b.getLong();
            case 'F' -> (double) b.getFloat();
            case 'D' -> b.getDouble();
            case 'S', 'R' -> {
                int length = b.getInt();
                if (length < 0 || length > b.remaining()) throw new IOException("Повреждённый FBX");
                byte[] raw = new byte[length];
                b.get(raw);
                yield type == 'S' ? new String(raw, StandardCharsets.UTF_8) : raw;
            }
            case 'f', 'd', 'l', 'i', 'b' -> array(type);
            default -> throw new IOException("Неизвестный тип свойства FBX: " + type);
        };
    }

    private Object array(char type) throws IOException {
        int length = b.getInt(), encoding = b.getInt(), compressed = b.getInt();
        int size = switch (type) { case 'd', 'l' -> 8; case 'b' -> 1; default -> 4; };
        if (length < 0 || length > 50_000_000 || compressed < 0 || compressed > b.remaining()) throw new IOException("Повреждённый FBX");
        byte[] raw = new byte[compressed];
        b.get(raw);
        if (encoding == 1) {
            var inflater = new Inflater();
            inflater.setInput(raw);
            byte[] out = new byte[length * size];
            try {
                int done = 0;
                while (done < out.length && !inflater.finished()) {
                    int n = inflater.inflate(out, done, out.length - done);
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                    done += n;
                }
            } catch (DataFormatException e) {
                throw new IOException("Повреждённый FBX: " + e.getMessage());
            } finally {
                inflater.end();
            }
            raw = out;
        }
        var a = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case 'd' -> { double[] v = new double[length]; for (int i = 0; i < length; i++) v[i] = a.getDouble(); yield v; }
            case 'f' -> { double[] v = new double[length]; for (int i = 0; i < length; i++) v[i] = a.getFloat(); yield v; }
            case 'l' -> { long[] v = new long[length]; for (int i = 0; i < length; i++) v[i] = a.getLong(); yield v; }
            case 'i' -> { int[] v = new int[length]; for (int i = 0; i < length; i++) v[i] = a.getInt(); yield v; }
            default -> { int[] v = new int[length]; for (int i = 0; i < length; i++) v[i] = a.get(); yield v; }
        };
    }

    private static final class Scene {
        final Map<Long, Node> objects = new HashMap<>();
        /** child id -> parents in file order, and parent id -> children with the property name of "OP" links. */
        final Map<Long, List<Long>> parents = new HashMap<>();
        final Map<Long, List<Object[]>> children = new LinkedHashMap<>();
        final SoftModelLoader.Source source;
        final String folder;
        double unit = 1;
        int up = 1, upSign = 1;

        Scene(List<Node> top, SoftModelLoader.Source source, String folder) {
            this.source = source;
            this.folder = folder;
            for (var n : top) {
                if (n.name.equals("Objects")) for (var o : n.children) if (!o.props.isEmpty() && o.props.get(0) instanceof Long) objects.put(o.id(), o);
                if (n.name.equals("Connections")) for (var c : n.all("C")) {
                    if (c.props.size() < 3 || !(c.prop(1) instanceof Long child) || !(c.prop(2) instanceof Long parent)) continue;
                    parents.computeIfAbsent(child, k -> new ArrayList<>()).add(parent);
                    children.computeIfAbsent(parent, k -> new ArrayList<>()).add(new Object[]{child, c.props.size() > 3 ? c.prop(3) : null});
                }
                if (n.name.equals("GlobalSettings")) {
                    var p = properties(n);
                    if (p.containsKey("UnitScaleFactor")) unit = number(p.get("UnitScaleFactor"), 4, 1);
                    if (p.containsKey("UpAxis")) up = (int) number(p.get("UpAxis"), 4, 1);
                    if (p.containsKey("UpAxisSign")) upSign = (int) number(p.get("UpAxisSign"), 4, 1);
                }
            }
        }

        static Map<String, Node> properties(Node n) {
            Map<String, Node> out = new HashMap<>();
            var p = n.child("Properties70");
            if (p == null) p = n.child("Properties60");
            if (p != null) for (var e : p.children) if (!e.props.isEmpty() && e.prop(0) instanceof String s) out.put(s, e);
            return out;
        }
        static double number(Node p, int index, double fallback) { return p != null && p.prop(index) instanceof Number n ? n.doubleValue() : fallback; }
        static double[] vector(Map<String, Node> p, String key, double d) {
            var n = p.get(key);
            return new double[]{number(n, 4, d), number(n, 5, d), number(n, 6, d)};
        }
        static String objectName(Node n) {
            String s = n.prop(1) instanceof String x ? x : "";
            int cut = s.indexOf('\u0000');
            if (cut >= 0) s = s.substring(0, cut);
            return s.startsWith("Model::") || s.startsWith("Geometry::") ? s.substring(s.indexOf("::") + 2) : s;
        }

        double[] local(Node model) {
            var p = properties(model);
            double[] t = vector(p, "Lcl Translation", 0), r = vector(p, "Lcl Rotation", 0), s = vector(p, "Lcl Scaling", 1), pre = vector(p, "PreRotation", 0);
            double[] post = vector(p, "PostRotation", 0), rotPivot = vector(p, "RotationPivot", 0), rotOffset = vector(p, "RotationOffset", 0);
            double[] scalePivot = vector(p, "ScalingPivot", 0), scaleOffset = vector(p, "ScalingOffset", 0);
            double[] m = SoftModelLoader.translation(t[0], t[1], t[2]);
            m = SoftModelLoader.multiply(m, SoftModelLoader.translation(rotOffset[0] + rotPivot[0], rotOffset[1] + rotPivot[1], rotOffset[2] + rotPivot[2]));
            m = SoftModelLoader.multiply(m, rad(pre));
            m = SoftModelLoader.multiply(m, rad(r));
            m = SoftModelLoader.multiply(m, invert3(rad(post)));
            m = SoftModelLoader.multiply(m, SoftModelLoader.translation(-rotPivot[0] + scaleOffset[0] + scalePivot[0], -rotPivot[1] + scaleOffset[1] + scalePivot[1], -rotPivot[2] + scaleOffset[2] + scalePivot[2]));
            m = SoftModelLoader.multiply(m, SoftModelLoader.scaling(s[0], s[1], s[2]));
            return SoftModelLoader.multiply(m, SoftModelLoader.translation(-scalePivot[0], -scalePivot[1], -scalePivot[2]));
        }
        static double[] rad(double[] d) { return SoftModelLoader.euler(Math.toRadians(d[0]), Math.toRadians(d[1]), Math.toRadians(d[2])); }
        static double[] invert3(double[] m) {
            double[] r = SoftModelLoader.identity();
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i * 4 + j] = m[j * 4 + i];
            return r;
        }

        double[] world(long id, int depth) {
            var node = objects.get(id);
            if (node == null || depth > 64 || !node.name.equals("Model")) return SoftModelLoader.identity();
            double[] parent = SoftModelLoader.identity();
            for (long p : parents.getOrDefault(id, List.of()))
                if (objects.containsKey(p) && objects.get(p).name.equals("Model")) { parent = world(p, depth + 1); break; }
            return SoftModelLoader.multiply(parent, local(node));
        }

        String path(long id, int depth) {
            var node = objects.get(id);
            if (node == null || depth > 64) return "";
            for (long p : parents.getOrDefault(id, List.of()))
                if (objects.containsKey(p) && objects.get(p).name.equals("Model")) {
                    String parent = path(p, depth + 1);
                    return parent.isEmpty() ? objectName(node) : parent + "/" + objectName(node);
                }
            return objectName(node);
        }

        SoftModel build() throws IOException {
            var model = new SoftModel();
            Map<Long, Integer> materials = new HashMap<>();
            // Z-up files become Y-up; the unit factor is centimetres per file unit.
            double[] axes = up == 2 ? new double[]{1, 0, 0, 0, 0, 0, -upSign, 0, 0, upSign, 0, 0, 0, 0, 0, 1} : SoftModelLoader.identity();
            double[] root = SoftModelLoader.multiply(SoftModelLoader.scaling(unit / 100, unit / 100, unit / 100), axes);
            for (var entry : objects.entrySet()) {
                var geometry = entry.getValue();
                if (!geometry.name.equals("Geometry") || !"Mesh".equals(geometry.prop(2))) continue;
                for (long modelId : parents.getOrDefault(entry.getKey(), List.of())) {
                    var owner = objects.get(modelId);
                    if (owner == null || !owner.name.equals("Model")) continue;
                    var p = properties(owner);
                    double[] gt = vector(p, "GeometricTranslation", 0), gr = vector(p, "GeometricRotation", 0), gs = vector(p, "GeometricScaling", 1);
                    double[] geo = SoftModelLoader.multiply(SoftModelLoader.translation(gt[0], gt[1], gt[2]), SoftModelLoader.multiply(rad(gr), SoftModelLoader.scaling(gs[0], gs[1], gs[2])));
                    double[] m = SoftModelLoader.multiply(root, SoftModelLoader.multiply(world(modelId, 0), geo));
                    List<Integer> slots = new ArrayList<>();
                    for (Object[] c : children.getOrDefault(modelId, List.of())) {
                        var mat = objects.get((Long) c[0]);
                        if (mat != null && mat.name.equals("Material")) slots.add(materials.computeIfAbsent((Long) c[0], k -> material(model, k)));
                    }
                    if (slots.isEmpty()) slots.add(model.colour("default", 0xFFCCCCCC));
                    mesh(model, geometry, m, path(modelId, 0), slots);
                }
            }
            return model;
        }

        int material(SoftModel model, long id) {
            var node = objects.get(id);
            var p = properties(node);
            double[] c = p.containsKey("DiffuseColor") ? vector(p, "DiffuseColor", .8) : p.containsKey("Diffuse") ? vector(p, "Diffuse", .8) : new double[]{.8, .8, .8};
            double factor = p.containsKey("DiffuseFactor") ? number(p.get("DiffuseFactor"), 4, 1) : 1;
            double alpha = p.containsKey("Opacity") ? number(p.get("Opacity"), 4, 1) : 1 - (p.containsKey("TransparencyFactor") ? number(p.get("TransparencyFactor"), 4, 0) : 0);
            byte[] png = null;
            for (Object[] link : children.getOrDefault(id, List.of())) {
                var tex = objects.get((Long) link[0]);
                if (tex == null || !tex.name.equals("Texture")) continue;
                String slot = link[1] instanceof String s ? s : "";
                if (!slot.isEmpty() && !slot.startsWith("Diffuse") && !slot.equals("base_color_map") && !slot.equals("3dsMax|Parameters|base_color_map")) continue;
                png = texture(tex);
                if (png != null) break;
            }
            // A texture already carries the colour; multiplying by a grey diffuse would only darken it.
            if (png != null) c = new double[]{1, 1, 1};
            factor = Math.clamp(factor, 0, 1);
            model.materials.add(new SoftModel.Material(objectName(node), SoftModelLoader.argb(c[0] * (png != null ? 1 : factor), c[1] * (png != null ? 1 : factor), c[2] * (png != null ? 1 : factor), Math.clamp(alpha, 0, 1)), png));
            return model.materials.size() - 1;
        }

        byte[] texture(Node tex) {
            for (Object[] link : children.getOrDefault(tex.id(), List.of())) {
                var video = objects.get((Long) link[0]);
                if (video != null && video.name.equals("Video") && video.child("Content") != null && video.child("Content").prop(0) instanceof byte[] raw && raw.length > 0) {
                    byte[] png = SoftModelLoader.texture(raw);
                    if (png != null) return png;
                }
            }
            for (String key : List.of("RelativeFilename", "FileName", "Filename")) {
                var n = tex.child(key);
                if (n != null && n.prop(0) instanceof String path && !path.isBlank()) {
                    byte[] png = SoftModelLoader.texture(source, folder + path.replace('\\', '/'));
                    if (png != null) return png;
                }
            }
            return null;
        }

        void mesh(SoftModel model, Node g, double[] m, String name, List<Integer> slots) throws IOException {
            double[] v = g.child("Vertices") != null && g.child("Vertices").prop(0) instanceof double[] d ? d : null;
            int[] poly = g.child("PolygonVertexIndex") != null && g.child("PolygonVertexIndex").prop(0) instanceof int[] i ? i : null;
            if (v == null || poly == null) return;
            double[] uv = null;
            int[] uvIndex = null;
            boolean uvByVertex = false;
            var layer = g.child("LayerElementUV");
            if (layer != null) {
                uv = layer.child("UV") != null && layer.child("UV").prop(0) instanceof double[] d ? d : null;
                uvIndex = layer.child("UVIndex") != null && layer.child("UVIndex").prop(0) instanceof int[] i ? i : null;
                uvByVertex = layer.child("MappingInformationType") != null && String.valueOf(layer.child("MappingInformationType").prop(0)).startsWith("ByVert");
                if (layer.child("ReferenceInformationType") != null && "Direct".equals(layer.child("ReferenceInformationType").prop(0))) uvIndex = null;
            }
            int[] polygonMaterial = null;
            boolean allSame = true;
            var mats = g.child("LayerElementMaterial");
            if (mats != null && mats.child("Materials") != null && mats.child("Materials").prop(0) instanceof int[] i) {
                polygonMaterial = i;
                allSame = mats.child("MappingInformationType") != null && "AllSame".equals(mats.child("MappingInformationType").prop(0));
            }
            boolean flip = SoftModelLoader.det3(m) < 0;
            Map<Integer, SoftModelLoader.Builder> builders = new LinkedHashMap<>();
            int start = 0, polygon = 0;
            List<Integer> corners = new ArrayList<>();
            for (int k = 0; k < poly.length; k++) {
                int index = poly[k] < 0 ? ~poly[k] : poly[k];
                if (index * 3 + 2 >= v.length) throw new IOException("FBX: неверный индекс вершины");
                corners.add(k);
                if (poly[k] >= 0) continue;
                int slot = polygonMaterial == null || polygonMaterial.length == 0 ? 0 : allSame ? polygonMaterial[0] : polygon < polygonMaterial.length ? polygonMaterial[polygon] : 0;
                int material = slots.get(Math.clamp(slot, 0, slots.size() - 1));
                var b = builders.computeIfAbsent(material, x -> new SoftModelLoader.Builder(name, x));
                int[] out = new int[corners.size()];
                for (int c = 0; c < corners.size(); c++) {
                    int pk = corners.get(c), vi = poly[pk] < 0 ? ~poly[pk] : poly[pk];
                    double[] p = SoftModelLoader.transform(m, new double[]{v[vi * 3], v[vi * 3 + 1], v[vi * 3 + 2]});
                    double u = 0, w = 0;
                    if (uv != null) {
                        int ui = uvByVertex ? vi : pk;
                        if (uvIndex != null) ui = ui < uvIndex.length ? uvIndex[ui] : -1;
                        if (ui >= 0 && ui * 2 + 1 < uv.length) { u = uv[ui * 2]; w = 1 - uv[ui * 2 + 1]; }
                    }
                    out[c] = b.vertex(p[0], p[1], p[2], u, w);
                }
                for (int c = 1; c + 1 < out.length; c++) if (flip) b.triangle(out[0], out[c + 1], out[c]); else b.triangle(out[0], out[c], out[c + 1]);
                corners.clear();
                polygon++;
                start = k + 1;
            }
            for (var b : builders.values()) b.add(model);
        }
    }
}
