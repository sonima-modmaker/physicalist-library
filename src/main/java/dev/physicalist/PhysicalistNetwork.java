package dev.physicalist;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class PhysicalistNetwork {
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PhysicalistLibrary.MOD_ID);
        registrar.playToServer(WandControlPacket.TYPE, WandControlPacket.STREAM_CODEC, WandControlPacket::handle);
    }
    private PhysicalistNetwork() {}
}
