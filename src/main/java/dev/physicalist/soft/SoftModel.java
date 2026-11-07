package dev.physicalist.soft;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.*;

/**
 * A loaded 3D model in a format-neutral form: named triangle meshes (Y up, model units) and their materials. It is
 * what travels from the client that picked the file to the server and from the server to every viewer.
 */
public final class SoftModel {
    /** color is ARGB; png is the base colour texture or null for a plain colour. */
    public record Material(String name, int color, byte[] png) {}
    /** positions xyz, uvs (top-left origin) per vertex, three indices per triangle. */
    public record Mesh(String name, int material, float[] positions, float[] uvs, int[] indices) {
        public int vertices() { return positions.length / 3; }
    }
    public static final int MAX_TRIANGLES = 300_000, MAX_VERTICES = 600_000, MAX_MATERIALS = 128, MAX_BYTES = 12 << 20;
    private static final int MAGIC = 0x50534d31;

    public final List<Material> materials = new ArrayList<>();
    public final List<Mesh> meshes = new ArrayList<>();

    public int triangles() { int n = 0; for (var m : meshes) n += m.indices.length / 3; return n; }

    /** Material index for a plain colour, shared by every mesh that uses it. */
    public int colour(String name, int argb) {
        for (int i = 0; i < materials.size(); i++) {
            var m = materials.get(i);
            if (m.png == null && m.color == argb && m.name.equals(name)) return i;
        }
        materials.add(new Material(name, argb, null));
        return materials.size() - 1;
    }

    /** Throws when the model is empty, too large or refers to things that do not exist. */
    public void validate() throws IOException {
        if (meshes.isEmpty() || triangles() == 0) throw new IOException("В модели нет треугольников");
        if (triangles() > MAX_TRIANGLES) throw new IOException("Слишком много треугольников: " + triangles() + " (максимум " + MAX_TRIANGLES + ")");
        int vertices = 0;
        for (var m : meshes) {
            vertices += m.vertices();
            if (m.positions.length % 3 != 0 || m.uvs.length != m.vertices() * 2 || m.indices.length % 3 != 0)
                throw new IOException("Повреждённая сетка " + m.name);
            if (m.material < 0 || m.material >= materials.size()) throw new IOException("Нет материала у " + m.name);
            for (float f : m.positions) if (!Float.isFinite(f)) throw new IOException("Некорректные координаты в " + m.name);
            for (int i : m.indices) if (i < 0 || i >= m.vertices()) throw new IOException("Некорректные индексы в " + m.name);
        }
        if (vertices > MAX_VERTICES) throw new IOException("Слишком много вершин: " + vertices);
        if (materials.size() > MAX_MATERIALS) throw new IOException("Слишком много материалов: " + materials.size());
    }

    public byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(bytes, new Deflater(6))))) {
            out.writeInt(MAGIC);
            out.writeInt(materials.size());
            for (var m : materials) {
                out.writeUTF(clip(m.name));
                out.writeInt(m.color);
                out.writeInt(m.png == null ? -1 : m.png.length);
                if (m.png != null) out.write(m.png);
            }
            out.writeInt(meshes.size());
            for (var m : meshes) {
                out.writeUTF(clip(m.name));
                out.writeInt(m.material);
                out.writeInt(m.positions.length);
                for (float f : m.positions) out.writeFloat(f);
                for (float f : m.uvs) out.writeFloat(f);
                out.writeInt(m.indices.length);
                for (int i : m.indices) out.writeInt(i);
            }
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("Модель слишком большая: " + (bytes.size() >> 20) + " МБ после сжатия (максимум 12)");
        return bytes.toByteArray();
    }

    public static SoftModel decode(byte[] blob) throws IOException {
        if (blob == null || blob.length == 0 || blob.length > MAX_BYTES) throw new IOException("Нет данных модели");
        var model = new SoftModel();
        try (var in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(blob))))) {
            if (in.readInt() != MAGIC) throw new IOException("Не модель Physicalist");
            int materials = in.readInt();
            if (materials < 0 || materials > MAX_MATERIALS) throw new IOException("Материалов " + materials);
            for (int i = 0; i < materials; i++) {
                String name = in.readUTF();
                int color = in.readInt(), size = in.readInt();
                if (size > 64 << 20) throw new IOException("Текстура слишком большая");
                byte[] png = null;
                if (size >= 0) { png = new byte[size]; in.readFully(png); }
                model.materials.add(new Material(name, color, png));
            }
            int meshes = in.readInt();
            if (meshes < 0 || meshes > 65536) throw new IOException("Сеток " + meshes);
            for (int i = 0; i < meshes; i++) {
                String name = in.readUTF();
                int material = in.readInt(), positions = in.readInt();
                if (positions < 0 || positions > MAX_VERTICES * 3 || positions % 3 != 0) throw new IOException("Вершин " + positions);
                float[] p = new float[positions], uv = new float[positions / 3 * 2];
                for (int k = 0; k < p.length; k++) p[k] = in.readFloat();
                for (int k = 0; k < uv.length; k++) uv[k] = in.readFloat();
                int indices = in.readInt();
                if (indices < 0 || indices > MAX_TRIANGLES * 3) throw new IOException("Индексов " + indices);
                int[] index = new int[indices];
                for (int k = 0; k < index.length; k++) index[k] = in.readInt();
                model.meshes.add(new Mesh(name, material, p, uv, index));
            }
        }
        model.validate();
        return model;
    }

    private static String clip(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s; }
}
