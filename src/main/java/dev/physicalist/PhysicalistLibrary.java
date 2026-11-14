package dev.physicalist;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/** A standalone physics library with optional in-game testing tools. */
@Mod(PhysicalistLibrary.MOD_ID)
public final class PhysicalistLibrary {
    public static final String MOD_ID = "physicalist_library";

    public PhysicalistLibrary(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, PhysicalistConfig.SPEC);
        PhysicalistContent.ITEMS.register(modBus);
        PhysicalistContent.ENTITIES.register(modBus);
        PhysicalistContent.TABS.register(modBus);
        PhysicalistContent.SOUNDS.register(modBus);
        modBus.addListener(PhysicalistNetwork::register);
        NeoForge.EVENT_BUS.addListener(PhysicalistCommands::register);
        NeoForge.EVENT_BUS.addListener(NoCullPacket::onStartTracking);
        org.slf4j.LoggerFactory.getLogger("Physicalist").info("Physicalist 0.18 ragdolls: {}",dev.physicalist.nativecore.NativeBridge.STATUS);
        // Rigid-body steps run on a physics thread per dimension: started after the level tick, picked up before the next.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event)->{
            if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level){dev.physicalist.nativecore.NativeWorlds.poll(level);PhysicsWandItem.steerHeld(level);}
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.LevelTickEvent.Post event)->{
            if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)dev.physicalist.nativecore.NativeWorlds.launch(level);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload event)->{
            if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level){dev.physicalist.nativecore.NativeWorlds.close(level);dev.physicalist.soft.SoftWorlds.close(level);}
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event)->{
            dev.physicalist.nativecore.NativeWorlds.closeAll();dev.physicalist.soft.SoftWorlds.closeAll();PhysicalistBodies.clear();PhysicsMetrics.stop();PhysicsMetrics.reset();
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, PhysicalistRagdolls::onDeath);
        NeoForge.EVENT_BUS.addListener(PhysicalistRagdolls::tick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) ->
                PhysicalistWalkingCollision.recoverOverlap(event.getEntity()));
    }
}
