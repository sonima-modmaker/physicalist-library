package dev.physicalist.client;

import dev.physicalist.PhysicalistContent;
import dev.physicalist.PhysicalistLibrary;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = PhysicalistLibrary.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PhysicalistClient {
    @SubscribeEvent public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PhysicalistContent.PHYSICAL_CHAIN.get(), PhysicalChainRenderer::new);
        event.registerEntityRenderer(PhysicalistContent.PHYSICAL_BLOCK.get(), PhysicalBlockRenderer::new);
        event.registerEntityRenderer(PhysicalistContent.PLAYER_RAGDOLL.get(), PlayerRagdollRenderer::new);
        event.registerEntityRenderer(PhysicalistContent.SOFT_BODY.get(), SoftBodyRenderer::new);
    }
    @SubscribeEvent public static void registerKeys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(SoftBodyClient.RESET);
        event.register(SoftBodyClient.ENGINE);
        event.register(SoftBodyClient.BRAKE);
        event.register(SoftBodyClient.RUDDER_LEFT);
        event.register(SoftBodyClient.RUDDER_RIGHT);
    }
    private PhysicalistClient() {}
}
