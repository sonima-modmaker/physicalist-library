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
        NeoForge.EVENT_BUS.addListener(PhysicalistCommands::register);
    }
}
