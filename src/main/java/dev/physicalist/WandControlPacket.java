package dev.physicalist;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client wheel and Tab-mouse input; the server validates the held wand and target. */
public record WandControlPacket(int action, float horizontal, float vertical) implements CustomPacketPayload {
    public static final int DISTANCE = 0, ROTATE = 1;
    public static final Type<WandControlPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PhysicalistLibrary.MOD_ID, "wand_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WandControlPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> { buf.writeVarInt(packet.action); buf.writeFloat(packet.horizontal);
                buf.writeFloat(packet.vertical); },
            buf -> new WandControlPacket(buf.readVarInt(), buf.readFloat(), buf.readFloat()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(WandControlPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)
                    || !player.getAbilities().instabuild || !player.isUsingItem()
                    || !player.getUseItem().is(PhysicalistContent.PHYSICS_WAND.get())) return;
            if (packet.action == DISTANCE && Float.isFinite(packet.horizontal))
                PhysicsWandItem.adjustDistance(player, Math.signum(packet.horizontal));
            else if (packet.action == ROTATE && Float.isFinite(packet.horizontal)
                    && Float.isFinite(packet.vertical))
                PhysicsWandItem.rotate(player, packet.horizontal, packet.vertical);
        });
    }
}
