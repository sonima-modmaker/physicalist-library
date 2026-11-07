package dev.physicalist.soft;

import dev.physicalist.PhysicalistContent;
import dev.physicalist.PhysicalistLibrary;
import dev.physicalist.nativecore.NativeSoft;
import io.netty.buffer.ByteBuf;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Model upload from the menu, R to respawn, model download to viewers and per-tick node positions. */
public final class SoftPackets {
    private static final int UP_CHUNK = 30_000, DOWN_CHUNK = 900_000;
    private static ResourceLocation id(String s) { return ResourceLocation.fromNamespaceAndPath(PhysicalistLibrary.MOD_ID, s); }

    /** One piece of a model the player picked; the last piece spawns it. */
    public record Upload(int upload, int index, int total, byte[] data, double scale, double speed) implements CustomPacketPayload {
        public static final Type<Upload> TYPE = new Type<>(id("soft_upload"));
        public static final StreamCodec<FriendlyByteBuf, Upload> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.upload); b.writeVarInt(p.index); b.writeVarInt(p.total); b.writeByteArray(p.data); b.writeDouble(p.scale); b.writeDouble(p.speed);
        }, b -> new Upload(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readByteArray(UP_CHUNK + 16), b.readDouble(), b.readDouble()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Reset() implements CustomPacketPayload {
        public static final Type<Reset> TYPE = new Type<>(id("soft_reset"));
        public static final StreamCodec<ByteBuf, Reset> CODEC = StreamCodec.unit(new Reset());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Model(int entity, int index, int total, double scale, byte[] data) implements CustomPacketPayload {
        public static final Type<Model> TYPE = new Type<>(id("soft_model"));
        public static final StreamCodec<FriendlyByteBuf, Model> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.entity); b.writeVarInt(p.index); b.writeVarInt(p.total); b.writeDouble(p.scale); b.writeByteArray(p.data);
        }, b -> new Model(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readDouble(), b.readByteArray(DOWN_CHUNK + 16)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Node positions relative to (x, y, z) after simulation step `tick`; components and the live-beam bits only when
     * beams broke since the last packet; per rotor {angle, omega, thrust, throttle, on}; control deflections.
     */
    public record State(int entity, long tick, double x, double y, double z, float[] nodes, int version, int[] components,
                        float[] rotors, float[] controls, byte[] beams) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(id("soft_state"));
        public static final StreamCodec<FriendlyByteBuf, State> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.entity); b.writeVarLong(p.tick); b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
            b.writeVarInt(p.nodes.length); for (float f : p.nodes) b.writeFloat(f);
            b.writeVarInt(p.version);
            b.writeVarInt(p.components.length); for (int c : p.components) b.writeVarInt(c);
            b.writeVarInt(p.rotors.length); for (float f : p.rotors) b.writeFloat(f);
            b.writeVarInt(p.controls.length); for (float f : p.controls) b.writeFloat(f);
            b.writeByteArray(p.beams);
        }, b -> {
            int entity = b.readVarInt();
            long tick = b.readVarLong();
            double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
            int n = b.readVarInt();
            if (n < 0 || n > 30_000) throw new IllegalArgumentException("too many soft-body nodes");
            float[] nodes = new float[n];
            for (int i = 0; i < n; i++) nodes[i] = b.readFloat();
            int version = b.readVarInt(), c = b.readVarInt();
            if (c < 0 || c > 10_000) throw new IllegalArgumentException("too many soft-body nodes");
            int[] comps = new int[c];
            for (int i = 0; i < c; i++) comps[i] = b.readVarInt();
            float[] rotors = floats(b, 64), controls = floats(b, 16);
            return new State(entity, tick, x, y, z, nodes, version, comps, rotors, controls, b.readByteArray(16_384));
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static float[] floats(FriendlyByteBuf b, int max) {
        int n = b.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("bad soft-body packet");
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = b.readFloat();
        return out;
    }

    /** The pilot's stick, pedals, throttle, engine switch and brakes, every tick while seated. */
    public record Controls(int entity, float pitch, float roll, float pedals, float throttle, boolean engine, boolean brake) implements CustomPacketPayload {
        public static final Type<Controls> TYPE = new Type<>(id("soft_controls"));
        public static final StreamCodec<FriendlyByteBuf, Controls> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.entity); b.writeFloat(p.pitch); b.writeFloat(p.roll); b.writeFloat(p.pedals); b.writeFloat(p.throttle);
            b.writeBoolean(p.engine); b.writeBoolean(p.brake);
        }, b -> new Controls(b.readVarInt(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readBoolean(), b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar(PhysicalistLibrary.MOD_ID);
        r.playToServer(Upload.TYPE, Upload.CODEC, SoftPackets::upload);
        r.playToServer(Reset.TYPE, Reset.CODEC, SoftPackets::reset);
        r.playToServer(Controls.TYPE, Controls.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player && player.getVehicle() instanceof SoftBodyEntity e && e.getId() == p.entity()
                    && Float.isFinite(p.pitch()) && Float.isFinite(p.roll()) && Float.isFinite(p.pedals()) && Float.isFinite(p.throttle()))
                e.input(p.pitch(), p.roll(), p.pedals(), p.throttle(), p.engine(), p.brake());
        }));
        r.playToClient(Model.TYPE, Model.CODEC, (p, c) -> c.enqueueWork(() -> dev.physicalist.client.SoftBodyClient.model(p)));
        r.playToClient(State.TYPE, State.CODEC, (p, c) -> c.enqueueWork(() -> dev.physicalist.client.SoftBodyClient.state(p)));
    }

    // ------------------------------------------------------------ client -> server

    /** Splits an encoded model into upload packets. */
    public static List<Upload> pieces(byte[] blob, double scale, double speed) {
        int upload = new Random().nextInt(Integer.MAX_VALUE), total = Math.max(1, (blob.length + UP_CHUNK - 1) / UP_CHUNK);
        List<Upload> out = new ArrayList<>();
        for (int i = 0; i < total; i++) out.add(new Upload(upload, i, total, Arrays.copyOfRange(blob, i * UP_CHUNK, Math.min(blob.length, (i + 1) * UP_CHUNK)), scale, speed));
        return out;
    }

    private static final Map<UUID, byte[][]> UPLOADS = new HashMap<>();
    private static final Map<UUID, Integer> UPLOAD_IDS = new HashMap<>();

    private static void upload(Upload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!dev.physicalist.PhysicalistConfig.softBodies()) {
                player.displayClientMessage(Component.translatable("message.physicalist_library.soft_experimental"), false);
                return;
            }
            if (!player.getAbilities().instabuild || !(player.getMainHandItem().is(PhysicalistContent.PHYSICALIST.get()) || player.getOffhandItem().is(PhysicalistContent.PHYSICALIST.get()))) {
                player.displayClientMessage(Component.translatable("message.physicalist_library.soft_creative"), false);
                return;
            }
            if (p.total <= 0 || p.total > SoftModel.MAX_BYTES / UP_CHUNK + 1 || p.index < 0 || p.index >= p.total) return;
            if (!Objects.equals(UPLOAD_IDS.get(player.getUUID()), p.upload)) { UPLOAD_IDS.put(player.getUUID(), p.upload); UPLOADS.put(player.getUUID(), new byte[p.total][]); }
            byte[][] parts = UPLOADS.get(player.getUUID());
            if (parts.length != p.total) return;
            parts[p.index] = p.data;
            for (byte[] part : parts) if (part == null) return;
            UPLOADS.remove(player.getUUID());
            UPLOAD_IDS.remove(player.getUUID());
            int size = 0;
            for (byte[] part : parts) size += part.length;
            byte[] blob = new byte[size];
            int at = 0;
            for (byte[] part : parts) { System.arraycopy(part, 0, blob, at, part.length); at += part.length; }
            spawn(player, blob, Math.clamp(p.scale, 0.05, 20), Math.clamp(p.speed, 0, 120));
        });
    }

    private static void spawn(ServerPlayer player, byte[] blob, double scale, double speed) {
        if (!NativeSoft.AVAILABLE) {
            player.displayClientMessage(Component.translatable("message.physicalist_library.soft_native"), false);
            return;
        }
        var server = player.server;
        // Building the structure of a big model takes a moment: off the server thread, then spawn on it.
        CompletableFuture.supplyAsync(() -> {
            try { return SoftStructure.build(SoftModel.decode(blob), scale); }
            catch (Exception error) { throw new RuntimeException(error.getMessage(), error); }
        }).whenComplete((structure, error) -> server.execute(() -> {
            if (error != null || structure == null) {
                String why = error == null ? "?" : error.getCause() != null && error.getCause().getMessage() != null ? error.getCause().getMessage() : error.toString();
                player.displayClientMessage(Component.translatable("message.physicalist_library.soft_failed", why), false);
                return;
            }
            var level = player.serverLevel();
            double reach = 0;
            for (int i = 0; i < structure.rest.length; i += 3) reach = Math.max(reach, Math.hypot(structure.rest[i], structure.rest[i + 2]));
            Vec3 look = player.getLookAngle(), flat = new Vec3(look.x, 0, look.z);
            flat = flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
            Vec3 base = player.position().add(flat.scale(reach + 2)).add(0, 0.3, 0);
            float yaw = (float) Math.toDegrees(Math.atan2(-flat.x, flat.z));
            // Minecraft yaw: the model's +Z (its front) faces where the player looks.
            var entity = SoftBodyEntity.spawn(level, blob, structure, scale, base, yaw, look.scale(speed));
            if (!level.addFreshEntity(entity)) return;
            SoftBodyEntity.LAST.put(player.getUUID(), entity.getUUID());
            // Something with a seat: the player gets in straight away, engine running (cruise power when launched).
            boolean seated = structure.hasSeat() && player.startRiding(entity, true);
            if (seated && structure.rotorCount() > 0) entity.startEngine(speed > 1 ? 0.75 : 0);
            if (seated) { player.displayClientMessage(Component.translatable("message.physicalist_library.soft_seated"), false); return; }
            player.displayClientMessage(Component.translatable("message.physicalist_library.soft_spawned", structure.nodes, structure.beamCount(),
                    Math.round(structure.totalMass()), structure.doors.size()), true);
        }));
    }

    private static void reset(Reset p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.getAbilities().instabuild) return;
            var level = player.serverLevel();
            Vec3 eye = player.getEyePosition(), end = eye.add(player.getLookAngle().scale(96));
            SoftBodyEntity target = null;
            double best = Double.MAX_VALUE;
            for (var e : level.getEntitiesOfClass(SoftBodyEntity.class, new AABB(eye, eye).inflate(96))) {
                var hit = e.getBoundingBox().clip(eye, end);
                if (hit.isPresent() && hit.get().distanceTo(eye) < best) { best = hit.get().distanceTo(eye); target = e; }
            }
            if (target == null && SoftBodyEntity.LAST.containsKey(player.getUUID()) && level.getEntity(SoftBodyEntity.LAST.get(player.getUUID())) instanceof SoftBodyEntity last) target = last;
            if (target == null) return;
            target.respawn();
            player.displayClientMessage(Component.translatable("message.physicalist_library.soft_respawned"), true);
        });
    }

    // ------------------------------------------------------------ server -> client

    static void sendModel(ServerPlayer player, SoftBodyEntity entity) {
        byte[] blob = entity.blob();
        int total = Math.max(1, (blob.length + DOWN_CHUNK - 1) / DOWN_CHUNK);
        for (int i = 0; i < total; i++)
            PacketDistributor.sendToPlayer(player, new Model(entity.getId(), i, total, entity.userScale(), Arrays.copyOfRange(blob, i * DOWN_CHUNK, Math.min(blob.length, (i + 1) * DOWN_CHUNK))));
    }

    private SoftPackets() {}
}
