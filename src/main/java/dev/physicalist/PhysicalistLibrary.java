package dev.physicalist;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/** A standalone, opt-in physics library. It registers no weapons or entities. */
@Mod(PhysicalistLibrary.MOD_ID)
public final class PhysicalistLibrary {
    public static final String MOD_ID = "physicalist_library";

    public PhysicalistLibrary(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, PhysicalistConfig.SPEC);
    }
}
