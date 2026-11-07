package dev.physicalist.soft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Development only (-Dphysicalist.softphoto=true): the sample car and plane in a flat world, a door opened, a car
 * dropped hard; screenshots of each, then quit. Lets soft-body drawing and denting be judged by eye.
 */
@EventBusSubscriber(modid = "physicalist_library", value = Dist.CLIENT)
public final class SoftPhotoBooth {
    private static final boolean ENABLED = Boolean.getBoolean("physicalist.softphoto");
    private static final int[] SHOTS = {150, 190, 230, 300, 340};
    private static boolean started;
    private static int ticks = -1, shot;
    private static SoftBodyEntity car, plane, flyer;
    private static long flyerStart;
    private static Vec3 ground;

    @SubscribeEvent
    public static void title(ScreenEvent.Init.Post event) {
        if (!ENABLED || started || !(event.getScreen() instanceof TitleScreen)) return;
        started = true;
        var rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        Minecraft.getInstance().createWorldOpenFlows().createFreshLevel("physicalist-soft-" + System.currentTimeMillis(),
                new LevelSettings("Physicalist soft", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                new WorldOptions(1234, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                event.getScreen());
    }

    private static SoftBodyEntity spawn(ServerLevel level, String name, Vec3 base, float yaw, Vec3 launch) {
        try {
            byte[] blob = SoftModelLoader.load(SoftModelLoader.resources("/assets/physicalist_library/softbody"), name + ".obj", null).encode();
            var e = SoftBodyEntity.spawn(level, blob, SoftStructure.build(SoftModel.decode(blob), 1), 1, base, yaw, launch);
            level.addFreshEntity(e);
            return e;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!ENABLED || mc.player == null || mc.getSingleplayerServer() == null) return;
        ticks++;
        // Another window may take the focus away: never photograph the pause menu.
        if (ticks > 40 && mc.screen != null) mc.setScreen(null);
        var server = mc.getSingleplayerServer();
        if (ticks == 40) {
            mc.options.hideGui = true;
            mc.options.pauseOnLostFocus = false;
            server.execute(() -> {
                var level = server.overworld();
                level.setDayTime(6000);
                var player = server.getPlayerList().getPlayers().getFirst();
                ground = player.position();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(level, ground.x - 7, ground.y + 4.5, ground.z - 2, -60, 22);
                car = spawn(level, "car", ground.add(0, 0.2, 6), 90, Vec3.ZERO);
                plane = spawn(level, "plane", ground.add(0, 0.2, 18), 60, Vec3.ZERO);
                // Engine idling, stick back and right, brakes on: propeller and control surfaces visible.
                plane.testPilot = true;
                plane.input(-1, 1, 0.6f, 0.15f, true, true);
                // A second one flying low over the ground at full power: how the threaded simulation keeps up.
                flyer = spawn(level, "plane", ground.add(-40, 8, -60), 0, new Vec3(0, 0, 38));
                flyer.testPilot = true;
                flyer.input(-0.25f, 0, 0, 1, true, false);
            });
        }
        if (ticks == 60) server.execute(() -> flyerStart = flyer.applied == null ? 0 : flyer.applied.tick());
        if (ticks == 160) server.execute(() -> org.slf4j.LoggerFactory.getLogger("Physicalist").info("Threaded flight over 100 ticks: {} simulation steps, step {} ms, server {} ms per tick, soft-body server thread {} ms per tick",
                flyer.applied == null ? -1 : flyer.applied.tick() - flyerStart, String.format("%.1f", SoftWorlds.stepMillis(server.overworld())),
                String.format("%.2f", server.getAverageTickTimeNanos() / 1e6), String.format("%.3f", flyer.ownNanos / 1e6 / Math.max(1, flyer.ownTicks))));
        if (ticks == 100) server.execute(() -> { if (car != null) car.toggle(0); });
        if (ticks == 160) server.execute(() -> spawn(server.overworld(), "car", ground.add(5, 6, 9), 20, new Vec3(0, -28, 0)));
        if (ticks == 180) mc.getEntityRenderDispatcher().setRenderHitBoxes(true);
        if (ticks == 200) mc.getEntityRenderDispatcher().setRenderHitBoxes(false);
        if (ticks == 215) server.execute(() -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAbilities().flying = false;
            player.startRiding(plane, true);
        });
        if (ticks == 260) server.execute(() -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
        if (ticks == 310) server.execute(() -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(server.overworld(), ground.x + 1, ground.y + 3.5, ground.z + 4, -39, 25);
        });
        if (shot < SHOTS.length && ticks == SHOTS[shot]) {
            Screenshot.grab(mc.gameDirectory, "physicalist-soft-" + shot + ".png", mc.getMainRenderTarget(), message -> {});
            shot++;
            if (shot == SHOTS.length) mc.stop();
        }
    }

    private SoftPhotoBooth() {}
}
