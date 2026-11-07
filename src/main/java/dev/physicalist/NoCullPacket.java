package dev.physicalist;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Marks a physics body as never culled on clients. Culling mods (Entity Culling's tick culling) freeze an entity the
 * moment it leaves the screen; a held body that lagged a frame behind a fast camera turn then stayed frozen out of
 * view until the player looked back at the stale spot. Bodies from other mods are only known to the server.
 */
public record NoCullPacket(int entity) implements CustomPacketPayload {
    public static final Type<NoCullPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PhysicalistLibrary.MOD_ID, "no_cull"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NoCullPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.entity), buf -> new NoCullPacket(buf.readVarInt()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(NoCullPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Entity entity = context.player().level().getEntity(packet.entity);
            if (entity != null) entity.noCulling = true;
        });
    }

    /** Called when the server first tracks a body; players who start tracking later are covered by {@link #onStartTracking}. */
    static void mark(Entity entity) {
        if (entity.noCulling) return;
        entity.noCulling = true;
        PacketDistributor.sendToPlayersTrackingEntity(entity, new NoCullPacket(entity.getId()));
    }

    static void onStartTracking(net.neoforged.neoforge.event.entity.player.PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && target.noCulling && PhysicalistBodies.find(target) != null)
            PacketDistributor.sendToPlayer(player, new NoCullPacket(target.getId()));
    }
}
