package dev.physicalist.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Development only (-Dphysicalist.photo=true): a flat world with a row of mob ragdolls, photographed standing and
 * after they collapse, then quits. Lets the per-part mob rendering be judged by eye.
 */
@EventBusSubscriber(modid = "physicalist_library", value = Dist.CLIENT)
public final class RagdollPhotoBooth {
    private static final boolean ENABLED = Boolean.getBoolean("physicalist.photo");
    private static final int[] SHOTS = {62, 200};
    private static boolean started;
    private static int ticks = -1, shot;

    @SubscribeEvent
    public static void title(ScreenEvent.Init.Post event) {
        if (!ENABLED || started || !(event.getScreen() instanceof TitleScreen)) return;
        started = true;
        var rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        Minecraft.getInstance().createWorldOpenFlows().createFreshLevel("physicalist-photo-" + System.currentTimeMillis(),
                new LevelSettings("Physicalist photo", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                new WorldOptions(1234, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                event.getScreen());
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
        ticks++;
        var server = mc.getSingleplayerServer();
        if (ticks == 40) {
            mc.options.hideGui = true;
            mc.options.pauseOnLostFocus = false;
            server.execute(() -> {
                var level = server.overworld();
                level.setDayTime(6000);
                var player = server.getPlayerList().getPlayers().getFirst();
                var ground = player.blockPosition();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(level, ground.getX() + .5, ground.getY() + 2.2, ground.getZ() - 4.5, 0, 18);
                EntityType<?>[] types = {EntityType.PIG, EntityType.COW, EntityType.SHEEP, EntityType.ZOMBIE, EntityType.VILLAGER, EntityType.WOLF, EntityType.CHICKEN, EntityType.SPIDER};
                for (int i = 0; i < types.length; i++) {
                    var mob = types[i].create(level);
                    mob.moveTo(ground.getX() + .5 + (i - 3.5) * 1.6, ground.getY() + .05, ground.getZ() + 2.5, 180, 0);
                    if (mob instanceof net.minecraft.world.entity.LivingEntity living) living.yBodyRot = 180;
                    var doll = dev.physicalist.PhysicalistContent.PLAYER_RAGDOLL.get().create(level);
                    doll.initialize(mob);
                    mob.discard();
                    level.addFreshEntity(doll);
                }
            });
        }
        if (shot < SHOTS.length && ticks == SHOTS[shot]) {
            Screenshot.grab(mc.gameDirectory, "physicalist-photo-" + shot + ".png", mc.getMainRenderTarget(), message -> {});
            shot++;
            if (shot == SHOTS.length) mc.stop();
        }
    }

    private RagdollPhotoBooth() {}
}
