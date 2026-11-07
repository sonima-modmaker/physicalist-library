package dev.physicalist.soft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import javax.imageio.ImageIO;

/**
 * Reads glTF 2.0 (.gltf/.glb), Wavefront OBJ with MTL, binary FBX, Minecraft/Blockbench JSON models (.json) and
 * Blockbench projects (.bbmodel) into a {@link SoftModel}. Textures next to the model (or embedded in it) are found
 * automatically.
 */
public final class SoftModelLoader {
    /** Where a model and the files it refers to (textures, .mtl, .bin) come from. */
    public interface Source {
        byte[] read(String path) throws IOException;
    }

    public static Source files(Path folder) {
        return path -> {
            Path p = folder.resolve(path.replace('\\', '/')).normalize();
            if (!Files.isRegularFile(p)) {
                // Exporters often store absolute paths from the author's machine: try the bare file name next to the model.
                String name = Path.of(path.replace('\\', '/')).getFileName().toString();
                for (Path candidate : List.of(folder.resolve(name), folder.resolve("textures").resolve(name), folder.resolve("Textures").resolve(name)))
                    if (Files.isRegularFile(candidate)) { p = candidate; break; }
            }
            if (Files.size(p) > 256L << 20) throw new IOException("Файл слишком большой: " + p.getFileName());
            return Files.readAllBytes(p);
        };
    }

    public static Source resources(String folder) {
        return path -> {
            try (var in = SoftModelLoader.class.getResourceAsStream(folder + "/" + path)) {
                if (in == null) throw new IOException("Нет ресурса " + path);
                return in.readAllBytes();
            }
        };
    }

    public static final List<String> EXTENSIONS = List.of("glb", "gltf", "obj", "fbx", "json", "bbmodel");

    /** minecraftTexture turns "namespace:path" texture references of Minecraft JSON models into PNG bytes, or null. */
    public static SoftModel load(Source source, String file, Function<String, byte[]> minecraftTexture) throws IOException {
        String ext = file.contains(".") ? file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        byte[] data = source.read(file);
        SoftModel model = switch (ext) {
            case "glb" -> gltf(source, file, data, true);
            case "gltf" -> gltf(source, file, data, false);
            case "obj" -> obj(source, file, data);
            case "fbx" -> FbxReader.read(source, file, data);
            case "json", "bbmodel" -> {
                var json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("asset") && json.has("meshes")) yield gltf(source, file, data, false);
                yield blocks(source, json, minecraftTexture);
            }
            default -> throw new IOException("Формат ." + ext + " не поддерживается (нужен " + String.join(", ", EXTENSIONS) + ")");
        };
        model.validate();
        return model;
    }

    // ---------------------------------------------------------------- textures

    /** Any image ImageIO reads (PNG, JPEG, BMP, GIF), at most 1024 px on a side, as PNG; null when it cannot be read. */
    public static byte[] texture(byte[] image) {
        if (image == null) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight(), scale = 1;
            while (w / scale > 1024 || h / scale > 1024) scale *= 2;
            BufferedImage out = new BufferedImage(Math.max(1, w / scale), Math.max(1, h / scale), BufferedImage.TYPE_INT_ARGB);
            var g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, out.getWidth(), out.getHeight(), null);
            g.dispose();
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    static byte[] texture(Source source, String path) {
        if (path == null || path.isBlank()) return null;
        try {
            if (path.startsWith("data:")) return texture(Base64.getDecoder().decode(path.substring(path.indexOf(',') + 1)));
            return texture(source.read(java.net.URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Texture {} not found: {}", path, error.toString());
            return null;
        }
    }

    static int argb(double r, double g, double b, double a) {
        return (int) Math.round(Math.clamp(a, 0, 1) * 255) << 24 | (int) Math.round(Math.clamp(r, 0, 1) * 255) << 16
                | (int) Math.round(Math.clamp(g, 0, 1) * 255) << 8 | (int) Math.round(Math.clamp(b, 0, 1) * 255);
    }

    static String folderOf(String file) {
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return slash < 0 ? "" : file.substring(0, slash + 1);
    }

    /** Collects vertices of one mesh until it is finished. */
    static final class Builder {
        final String name;
        final int material;
        float[] p = new float[192], uv = new float[128];
        int[] index = new int[192];
        int vertices, indices;
        Builder(String name, int material) { this.name = name; this.material = material; }
        int vertex(double x, double y, double z, double u, double v) {
            if (vertices * 3 + 3 > p.length) { p = Arrays.copyOf(p, p.length * 2); uv = Arrays.copyOf(uv, uv.length * 2); }
            p[vertices * 3] = (float) x; p[vertices * 3 + 1] = (float) y; p[vertices * 3 + 2] = (float) z;
            uv[vertices * 2] = (float) u; uv[vertices * 2 + 1] = (float) v;
            return vertices++;
        }
        void triangle(int a, int b, int c) {
            if (a == b || b == c || a == c) return;
            if (indices + 3 > index.length) index = Arrays.copyOf(index, index.length * 2);
            index[indices++] = a; index[indices++] = b; index[indices++] = c;
            if (indices / 3 > SoftModel.MAX_TRIANGLES) throw new IllegalStateException("Слишком много треугольников");
        }
        void add(SoftModel model) {
            if (indices == 0) return;
            model.meshes.add(new SoftModel.Mesh(name, material, Arrays.copyOf(p, vertices * 3), Arrays.copyOf(uv, vertices * 2), Arrays.copyOf(index, indices)));
        }
    }

    // ---------------------------------------------------------------- glTF 2.0

    private static SoftModel gltf(Source source, String file, byte[] data, boolean binary) throws IOException {
        JsonObject json;
        byte[] bin = null;
        if (binary) {
            var b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data.length < 20 || b.getInt(0) != 0x46546C67) throw new IOException("Не GLB-файл");
            int length = Math.min(b.getInt(8), data.length), at = 12;
            String text = null;
            while (at + 8 <= length) {
                int size = b.getInt(at), type = b.getInt(at + 4);
                if (size < 0 || at + 8 + size > length) break;
                if (type == 0x4E4F534A) text = new String(data, at + 8, size, StandardCharsets.UTF_8);
                else if (type == 0x004E4942 && bin == null) bin = Arrays.copyOfRange(data, at + 8, at + 8 + size);
                at += 8 + ((size + 3) & ~3);
            }
            if (text == null) throw new IOException("В GLB нет JSON");
            json = JsonParser.parseString(text).getAsJsonObject();
        } else json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        String folder = folderOf(file);
        List<byte[]> buffers = new ArrayList<>();
        for (var e : array(json, "buffers")) {
            var o = e.getAsJsonObject();
            if (!o.has("uri")) buffers.add(bin == null ? new byte[0] : bin);
            else {
                String uri = o.get("uri").getAsString();
                buffers.add(uri.startsWith("data:") ? Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1))
                        : source.read(folder + java.net.URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8)));
            }
        }
        var g = new Gltf(json, buffers, source, folder);
        var model = new SoftModel();
        JsonArray roots;
        var scenes = array(json, "scenes");
        if (scenes.size() > 0) roots = array(scenes.get(json.has("scene") ? json.get("scene").getAsInt() : 0).getAsJsonObject(), "nodes");
        else {
            // No scene: every node that is nobody's child.
            Set<Integer> children = new HashSet<>();
            for (var n : array(json, "nodes")) for (var c : array(n.getAsJsonObject(), "children")) children.add(c.getAsInt());
            roots = new JsonArray();
            for (int i = 0; i < array(json, "nodes").size(); i++) if (!children.contains(i)) roots.add(i);
        }
        for (var r : roots) g.node(model, r.getAsInt(), identity(), "", 0);
        if (model.meshes.isEmpty() && array(json, "nodes").isEmpty())
            for (int i = 0; i < array(json, "meshes").size(); i++) g.mesh(model, i, identity(), "mesh" + i);
        return model;
    }

    private static final class Gltf {
        final JsonObject json;
        final List<byte[]> buffers;
        final Source source;
        final String folder;
        final Map<Integer, Integer> materials = new HashMap<>();
        Gltf(JsonObject json, List<byte[]> buffers, Source source, String folder) {
            this.json = json; this.buffers = buffers; this.source = source; this.folder = folder;
        }

        void node(SoftModel model, int index, double[] parent, String path, int depth) {
            if (depth > 64) return;
            var node = array(json, "nodes").get(index).getAsJsonObject();
            double[] local;
            if (node.has("matrix")) {
                local = new double[16];
                var m = node.getAsJsonArray("matrix");
                for (int i = 0; i < 16; i++) local[i] = m.get(i).getAsDouble();
            } else {
                double[] t = vec(node, "translation", 0, 0, 0), s = vec(node, "scale", 1, 1, 1), q = {0, 0, 0, 1};
                if (node.has("rotation")) { var r = node.getAsJsonArray("rotation"); for (int i = 0; i < 4; i++) q[i] = r.get(i).getAsDouble(); }
                local = trs(t, q, s);
            }
            double[] world = multiply(parent, local);
            String name = node.has("name") ? node.get("name").getAsString() : "";
            String here = path.isEmpty() ? name : name.isEmpty() ? path : path + "/" + name;
            if (node.has("mesh")) {
                int mesh = node.get("mesh").getAsInt();
                var m = array(json, "meshes").get(mesh).getAsJsonObject();
                mesh(model, mesh, world, here.isEmpty() ? (m.has("name") ? m.get("name").getAsString() : "mesh" + mesh) : here);
            }
            for (var c : array(node, "children")) node(model, c.getAsInt(), world, here, depth + 1);
        }

        void mesh(SoftModel model, int index, double[] m, String name) {
            var mesh = array(json, "meshes").get(index).getAsJsonObject();
            boolean flip = det3(m) < 0;
            for (var pe : array(mesh, "primitives")) {
                var prim = pe.getAsJsonObject();
                int mode = prim.has("mode") ? prim.get("mode").getAsInt() : 4;
                var attributes = prim.getAsJsonObject("attributes");
                if (attributes == null || !attributes.has("POSITION") || mode < 4) continue;
                float[] pos = floats(attributes.get("POSITION").getAsInt());
                int count = pos.length / 3;
                float[] uv = attributes.has("TEXCOORD_0") ? floats(attributes.get("TEXCOORD_0").getAsInt()) : new float[count * 2];
                if (uv.length < count * 2) uv = Arrays.copyOf(uv, count * 2);
                int material = material(prim.has("material") ? prim.get("material").getAsInt() : -1, model);
                var b = new Builder(name, material);
                for (int i = 0; i < count; i++) {
                    double x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
                    b.vertex(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13],
                            m[2] * x + m[6] * y + m[10] * z + m[14], uv[i * 2], uv[i * 2 + 1]);
                }
                int[] idx;
                if (prim.has("indices")) idx = ints(prim.get("indices").getAsInt());
                else { idx = new int[count]; for (int i = 0; i < count; i++) idx[i] = i; }
                for (int i : idx) if (i < 0 || i >= count) return;
                if (mode == 4) for (int i = 0; i + 2 < idx.length; i += 3) tri(b, idx[i], idx[i + 1], idx[i + 2], flip);
                else if (mode == 5) for (int i = 0; i + 2 < idx.length; i++) tri(b, idx[i], idx[i + 1 + (i & 1)], idx[i + 2 - (i & 1)], flip);
                else if (mode == 6) for (int i = 1; i + 1 < idx.length; i++) tri(b, idx[0], idx[i], idx[i + 1], flip);
                b.add(model);
            }
        }

        static void tri(Builder b, int x, int y, int z, boolean flip) { if (flip) b.triangle(x, z, y); else b.triangle(x, y, z); }

        int material(int index, SoftModel model) {
            return materials.computeIfAbsent(index, i -> {
                if (i < 0 || i >= array(json, "materials").size()) return model.colour("default", 0xFFCCCCCC);
                var mat = array(json, "materials").get(i).getAsJsonObject();
                String name = mat.has("name") ? mat.get("name").getAsString() : "material" + i;
                double[] f = {1, 1, 1, 1};
                Integer texture = null;
                var pbr = mat.getAsJsonObject("pbrMetallicRoughness");
                if (pbr != null) {
                    if (pbr.has("baseColorFactor")) { var a = pbr.getAsJsonArray("baseColorFactor"); for (int k = 0; k < 4; k++) f[k] = a.get(k).getAsDouble(); }
                    if (pbr.has("baseColorTexture")) texture = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
                }
                var ext = mat.getAsJsonObject("extensions");
                if (texture == null && ext != null && ext.has("KHR_materials_pbrSpecularGlossiness")) {
                    var sg = ext.getAsJsonObject("KHR_materials_pbrSpecularGlossiness");
                    if (sg.has("diffuseTexture")) texture = sg.getAsJsonObject("diffuseTexture").get("index").getAsInt();
                    if (sg.has("diffuseFactor")) { var a = sg.getAsJsonArray("diffuseFactor"); for (int k = 0; k < 4; k++) f[k] = a.get(k).getAsDouble(); }
                }
                if (mat.has("alphaMode") && mat.get("alphaMode").getAsString().equals("OPAQUE")) f[3] = 1;
                byte[] png = texture == null ? null : image(texture);
                model.materials.add(new SoftModel.Material(name, argb(f[0], f[1], f[2], f[3]), png));
                return model.materials.size() - 1;
            });
        }

        byte[] image(int texture) {
            try {
                var tex = array(json, "textures").get(texture).getAsJsonObject();
                int source = tex.has("source") ? tex.get("source").getAsInt() : -1;
                var ext = tex.getAsJsonObject("extensions");
                if (source < 0 && ext != null) for (var e : ext.entrySet()) if (e.getValue().getAsJsonObject().has("source")) source = e.getValue().getAsJsonObject().get("source").getAsInt();
                if (source < 0) return null;
                var img = array(json, "images").get(source).getAsJsonObject();
                if (img.has("uri")) return texture(this.source, img.get("uri").getAsString().startsWith("data:") ? img.get("uri").getAsString() : folder + img.get("uri").getAsString());
                if (img.has("bufferView")) {
                    var view = array(json, "bufferViews").get(img.get("bufferView").getAsInt()).getAsJsonObject();
                    byte[] buffer = buffers.get(view.get("buffer").getAsInt());
                    int offset = view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0;
                    return texture(Arrays.copyOfRange(buffer, offset, offset + view.get("byteLength").getAsInt()));
                }
            } catch (RuntimeException ignored) {}
            return null;
        }

        float[] floats(int accessor) {
            var a = array(json, "accessors").get(accessor).getAsJsonObject();
            int count = a.get("count").getAsInt(), comps = switch (a.get("type").getAsString()) {
                case "SCALAR" -> 1; case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4; case "MAT4" -> 16; default -> 1;
            };
            if (count < 0 || count > SoftModel.MAX_VERTICES * 3) throw new IllegalStateException("accessor too large");
            int type = a.get("componentType").getAsInt(), size = switch (type) { case 5120, 5121 -> 1; case 5122, 5123 -> 2; default -> 4; };
            boolean normalized = a.has("normalized") && a.get("normalized").getAsBoolean();
            float[] out = new float[count * comps];
            if (!a.has("bufferView")) return out;
            var view = array(json, "bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
            var b = ByteBuffer.wrap(buffers.get(view.get("buffer").getAsInt())).order(ByteOrder.LITTLE_ENDIAN);
            int base = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0) + (a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0);
            int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : size * comps;
            for (int i = 0; i < count; i++) for (int k = 0; k < comps; k++) {
                int at = base + i * stride + k * size;
                float v = switch (type) {
                    case 5126 -> b.getFloat(at);
                    case 5121 -> normalized ? (b.get(at) & 255) / 255f : b.get(at) & 255;
                    case 5120 -> normalized ? Math.max(b.get(at) / 127f, -1) : b.get(at);
                    case 5123 -> normalized ? (b.getShort(at) & 65535) / 65535f : b.getShort(at) & 65535;
                    case 5122 -> normalized ? Math.max(b.getShort(at) / 32767f, -1) : b.getShort(at);
                    default -> b.getInt(at);
                };
                out[i * comps + k] = v;
            }
            return out;
        }

        int[] ints(int accessor) {
            var a = array(json, "accessors").get(accessor).getAsJsonObject();
            int count = a.get("count").getAsInt(), type = a.get("componentType").getAsInt();
            if (count < 0 || count > SoftModel.MAX_TRIANGLES * 3) throw new IllegalStateException("Слишком много индексов");
            var view = array(json, "bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();
            var b = ByteBuffer.wrap(buffers.get(view.get("buffer").getAsInt())).order(ByteOrder.LITTLE_ENDIAN);
            int base = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0) + (a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0);
            int size = type == 5121 ? 1 : type == 5123 ? 2 : 4;
            int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : size;
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                int at = base + i * stride;
                out[i] = type == 5121 ? b.get(at) & 255 : type == 5123 ? b.getShort(at) & 65535 : b.getInt(at);
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- OBJ + MTL

    private static SoftModel obj(Source source, String file, byte[] data) throws IOException {
        String folder = folderOf(file);
        var model = new SoftModel();
        List<double[]> v = new ArrayList<>(), vt = new ArrayList<>();
        Map<String, Integer> materials = new HashMap<>();
        Map<String, double[]> colours = new HashMap<>();
        Map<String, String> maps = new HashMap<>();
        Map<String, Builder> meshes = new LinkedHashMap<>();
        Map<Builder, Map<Long, Integer>> seen = new HashMap<>();
        String object = "object", material = "";
        for (String raw : new String(data, StandardCharsets.UTF_8).split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "v" -> v.add(new double[]{d(t, 1), d(t, 2), d(t, 3)});
                case "vt" -> vt.add(new double[]{d(t, 1), t.length > 2 ? d(t, 2) : 0});
                case "o", "g" -> object = t.length > 1 ? line.substring(t[0].length()).strip() : "object";
                case "usemtl" -> material = t.length > 1 ? line.substring(6).strip() : "";
                case "mtllib" -> {
                    try { mtl(source, folder + line.substring(6).strip(), colours, maps); }
                    catch (IOException error) { org.slf4j.LoggerFactory.getLogger("Physicalist").warn("MTL not found: {}", error.toString()); }
                }
                case "f" -> {
                    String key = object + "\u0000" + material;
                    Builder b = meshes.get(key);
                    if (b == null) {
                        String mat = material;
                        int index = materials.computeIfAbsent(mat, m -> {
                            double[] c = colours.getOrDefault(m, new double[]{.8, .8, .8, 1});
                            byte[] png = maps.containsKey(m) ? texture(source, folder + maps.get(m)) : null;
                            model.materials.add(new SoftModel.Material(m.isEmpty() ? "default" : m, argb(c[0], c[1], c[2], c[3]), png));
                            return model.materials.size() - 1;
                        });
                        b = new Builder(object, index);
                        meshes.put(key, b);
                    }
                    var cache = seen.computeIfAbsent(b, x -> new HashMap<>());
                    int[] corner = new int[t.length - 1];
                    for (int i = 1; i < t.length; i++) {
                        String[] parts = t[i].split("/");
                        int pi = ref(parts[0], v.size()), ti = parts.length > 1 && !parts[1].isEmpty() ? ref(parts[1], vt.size()) : -1;
                        if (pi < 0 || pi >= v.size()) throw new IOException("OBJ: неверная вершина " + t[i]);
                        long k = (long) pi << 32 | (ti + 1L);
                        Builder fb = b;
                        corner[i - 1] = cache.computeIfAbsent(k, x -> {
                            double[] p = v.get(pi), uv = ti >= 0 && ti < vt.size() ? vt.get(ti) : new double[2];
                            return fb.vertex(p[0], p[1], p[2], uv[0], 1 - uv[1]);
                        });
                    }
                    for (int i = 1; i + 1 < corner.length; i++) b.triangle(corner[0], corner[i], corner[i + 1]);
                }
                default -> {}
            }
        }
        for (var b : meshes.values()) b.add(model);
        return model;
    }

    private static int ref(String s, int size) { int i = Integer.parseInt(s); return i < 0 ? size + i : i - 1; }
    private static double d(String[] t, int i) { return i < t.length ? Double.parseDouble(t[i]) : 0; }

    private static void mtl(Source source, String path, Map<String, double[]> colours, Map<String, String> maps) throws IOException {
        String current = null;
        for (String raw : new String(source.read(path), StandardCharsets.UTF_8).split("\r?\n")) {
            String line = raw.strip();
            String[] t = line.split("\\s+");
            if (t.length < 2) continue;
            switch (t[0]) {
                case "newmtl" -> { current = line.substring(6).strip(); colours.put(current, new double[]{.8, .8, .8, 1}); }
                case "Kd" -> { if (current != null) { var c = colours.get(current); c[0] = d(t, 1); c[1] = d(t, 2); c[2] = d(t, 3); } }
                case "d" -> { if (current != null) colours.get(current)[3] = d(t, 1); }
                case "Tr" -> { if (current != null) colours.get(current)[3] = 1 - d(t, 1); }
                case "map_Kd" -> {
                    if (current == null) break;
                    // Options such as -s 1 1 1 come before the file name; the name itself may contain spaces.
                    int i = 1;
                    while (i < t.length - 1 && t[i].startsWith("-")) i += switch (t[i]) { case "-s", "-o", "-t" -> 4; case "-mm" -> 3; default -> 2; };
                    maps.put(current, String.join(" ", Arrays.copyOfRange(t, Math.min(i, t.length - 1), t.length)));
                }
                default -> {}
            }
        }
    }

    // ---------------------------------------------------------------- Minecraft / Blockbench JSON

    private static SoftModel blocks(Source source, JsonObject json, Function<String, byte[]> minecraftTexture) throws IOException {
        var model = new SoftModel();
        boolean project = json.has("meta");
        if (!json.has("elements")) throw new IOException("В JSON нет элементов (elements)");
        // Texture lookup: Blockbench keeps textures in an array (embedded), Minecraft models in a name map.
        Map<String, Integer> byKey = new HashMap<>();
        List<double[]> sizes = new ArrayList<>();
        double resW = 16, resH = 16;
        if (json.has("resolution")) { resW = json.getAsJsonObject("resolution").get("width").getAsDouble(); resH = json.getAsJsonObject("resolution").get("height").getAsDouble(); }
        if (json.has("texture_size")) { resW = json.getAsJsonArray("texture_size").get(0).getAsDouble(); resH = json.getAsJsonArray("texture_size").get(1).getAsDouble(); }
        if (project) {
            int i = 0;
            for (var t : array(json, "textures")) {
                var o = t.getAsJsonObject();
                byte[] png = o.has("source") ? texture(source, o.get("source").getAsString()) : null;
                if (png == null && o.has("relative_path")) png = texture(source, o.get("relative_path").getAsString());
                model.materials.add(new SoftModel.Material(o.has("name") ? o.get("name").getAsString() : "texture" + i, 0xFFFFFFFF, png));
                byKey.put(String.valueOf(i), model.materials.size() - 1);
                double w = o.has("uv_width") ? o.get("uv_width").getAsDouble() : resW, h = o.has("uv_height") ? o.get("uv_height").getAsDouble() : resH;
                sizes.add(new double[]{w, h});
                i++;
            }
        } else if (json.has("textures")) {
            var textures = json.getAsJsonObject("textures");
            for (var e : textures.entrySet()) {
                String ref = e.getValue().getAsString();
                for (int hops = 0; ref.startsWith("#") && hops < 8 && textures.has(ref.substring(1)); hops++) ref = textures.get(ref.substring(1)).getAsString();
                byte[] png = minecraftTexture == null ? null : minecraftTexture.apply(ref);
                if (png == null) png = texture(source, ref.contains(":") ? ref.substring(ref.indexOf(':') + 1) + ".png" : ref.endsWith(".png") ? ref : ref + ".png");
                model.materials.add(new SoftModel.Material(e.getKey(), 0xFFFFFFFF, png));
                byKey.put("#" + e.getKey(), model.materials.size() - 1);
                byKey.put(e.getKey(), model.materials.size() - 1);
            }
        }
        // Blockbench groups give the elements meaningful names ("door_left" holding "cube").
        Map<String, String> groups = new HashMap<>();
        if (json.has("outliner")) outline(json.getAsJsonArray("outliner"), "", groups);
        int plain = model.colour("default", 0xFFCCCCCC);
        Map<String, Builder> meshes = new LinkedHashMap<>();
        for (var e : array(json, "elements")) {
            var el = e.getAsJsonObject();
            if (el.has("visibility") && !el.get("visibility").getAsBoolean()) continue;
            if (!el.has("from") || !el.has("to")) continue;
            double[] from = vec(el, "from", 0, 0, 0), to = vec(el, "to", 0, 0, 0);
            String name = el.has("name") ? el.get("name").getAsString() : "cube";
            if (el.has("uuid") && groups.containsKey(el.get("uuid").getAsString())) name = groups.get(el.get("uuid").getAsString()) + "/" + name;
            if (project && el.has("inflate")) { double g = el.get("inflate").getAsDouble(); for (int k = 0; k < 3; k++) { from[k] -= g; to[k] += g; } }
            double[] rot = identity();
            if (el.has("rotation")) {
                if (el.get("rotation").isJsonArray()) {
                    double[] r = vec(el, "rotation", 0, 0, 0), o = vec(el, "origin", 8, 8, 8);
                    rot = aboutPoint(euler(Math.toRadians(r[0]), Math.toRadians(r[1]), Math.toRadians(r[2])), o);
                } else {
                    var r = el.getAsJsonObject("rotation");
                    double[] o = vec(r, "origin", 8, 8, 8);
                    double a = Math.toRadians(r.has("angle") ? r.get("angle").getAsDouble() : 0);
                    String axis = r.has("axis") ? r.get("axis").getAsString() : "y";
                    rot = aboutPoint(euler(axis.equals("x") ? a : 0, axis.equals("y") ? a : 0, axis.equals("z") ? a : 0), o);
                }
            }
            double x0 = from[0], y0 = from[1], z0 = from[2], x1 = to[0], y1 = to[1], z1 = to[2];
            var faces = el.getAsJsonObject("faces");
            if (faces == null) continue;
            for (var f : faces.entrySet()) {
                var face = f.getValue().getAsJsonObject();
                if (face.has("texture") && face.get("texture").isJsonNull()) continue;
                String tex = face.has("texture") ? face.get("texture").getAsString() : "";
                int material = byKey.getOrDefault(tex, plain);
                double[] size = project && !sizes.isEmpty() && material != plain ? sizes.get(Math.min(sizes.size() - 1, Integer.parseInt(tex.replaceAll("\\D", "0")))) : new double[]{project ? resW : 16, project ? resH : 16};
                double[][] c = switch (f.getKey()) {
                    case "north" -> new double[][]{{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
                    case "south" -> new double[][]{{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
                    case "east" -> new double[][]{{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
                    case "west" -> new double[][]{{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
                    case "up" -> new double[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
                    case "down" -> new double[][]{{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
                    default -> null;
                };
                if (c == null) continue;
                double[] uv = face.has("uv") ? vec4(face.getAsJsonArray("uv")) : new double[]{0, 0, size[0], size[1]};
                double u0 = uv[0] / size[0], v0 = uv[1] / size[1], u1 = uv[2] / size[0], v1 = uv[3] / size[1];
                double[][] t = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
                int turns = face.has("rotation") ? Math.floorMod(face.get("rotation").getAsInt() / 90, 4) : 0;
                String key = name + "\u0000" + material;
                String n = name;
                var b = meshes.computeIfAbsent(key, k -> new Builder(n, material));
                int[] corner = new int[4];
                for (int k = 0; k < 4; k++) {
                    double[] p = transform(rot, c[k]), tc = t[(k + turns) % 4];
                    corner[k] = b.vertex(p[0] / 16, p[1] / 16, p[2] / 16, tc[0], tc[1]);
                }
                b.triangle(corner[0], corner[1], corner[2]);
                b.triangle(corner[0], corner[2], corner[3]);
            }
        }
        for (var b : meshes.values()) b.add(model);
        return model;
    }

    private static void outline(JsonArray list, String path, Map<String, String> out) {
        for (var e : list) {
            if (e.isJsonPrimitive()) { if (!path.isEmpty()) out.put(e.getAsString(), path); continue; }
            var g = e.getAsJsonObject();
            String name = g.has("name") ? g.get("name").getAsString() : "group";
            outline(array(g, "children"), path.isEmpty() ? name : path + "/" + name, out);
        }
    }

    // ---------------------------------------------------------------- maths (4x4 column-major)

    static JsonArray array(JsonObject o, String key) { var e = o.get(key); return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray(); }
    static double[] vec(JsonObject o, String key, double x, double y, double z) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return new double[]{x, y, z};
        var a = o.getAsJsonArray(key);
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }
    private static double[] vec4(JsonArray a) { return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()}; }
    static double[] identity() { return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }
    static double[] multiply(double[] a, double[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++) for (int row = 0; row < 4; row++) {
            double s = 0;
            for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
            r[c * 4 + row] = s;
        }
        return r;
    }
    static double[] trs(double[] t, double[] q, double[] s) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double[] m = {
                (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0,
                (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0,
                (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0,
                t[0], t[1], t[2], 1};
        return m;
    }
    /** Rotation applying X, then Y, then Z (radians). */
    static double[] euler(double rx, double ry, double rz) {
        double[] x = {1, 0, 0, 0, 0, Math.cos(rx), Math.sin(rx), 0, 0, -Math.sin(rx), Math.cos(rx), 0, 0, 0, 0, 1};
        double[] y = {Math.cos(ry), 0, -Math.sin(ry), 0, 0, 1, 0, 0, Math.sin(ry), 0, Math.cos(ry), 0, 0, 0, 0, 1};
        double[] z = {Math.cos(rz), Math.sin(rz), 0, 0, -Math.sin(rz), Math.cos(rz), 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return multiply(z, multiply(y, x));
    }
    static double[] translation(double x, double y, double z) { double[] m = identity(); m[12] = x; m[13] = y; m[14] = z; return m; }
    static double[] scaling(double x, double y, double z) { double[] m = identity(); m[0] = x; m[5] = y; m[10] = z; return m; }
    private static double[] aboutPoint(double[] r, double[] o) { return multiply(translation(o[0], o[1], o[2]), multiply(r, translation(-o[0], -o[1], -o[2]))); }
    static double[] transform(double[] m, double[] p) {
        return new double[]{m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12], m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13], m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]};
    }
    static double det3(double[] m) {
        return m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2]);
    }

    private SoftModelLoader() {}
}
