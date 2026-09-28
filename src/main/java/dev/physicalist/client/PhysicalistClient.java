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
        event.registerEntityRenderer(PhysicalistContent.PHYSICAL_BLOCK.get(), PhysicalBlockRenderer::new);
    }
    private PhysicalistClient() {}
}
