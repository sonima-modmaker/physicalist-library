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
    private static final int[] SHOTS = {80, 106, 114, 130, 220};
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
                EntityType<?>[] types = {EntityType.VILLAGER, EntityType.VILLAGER, EntityType.VILLAGER, EntityType.WANDERING_TRADER, EntityType.WITCH, EntityType.PILLAGER, EntityType.ZOMBIE, EntityType.SHEEP, EntityType.PLAYER};
                var professions = new net.minecraft.world.entity.npc.VillagerProfession[]{net.minecraft.world.entity.npc.VillagerProfession.FARMER, net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN, net.minecraft.world.entity.npc.VillagerProfession.ARMORER};
                var biomes = new net.minecraft.world.entity.npc.VillagerType[]{net.minecraft.world.entity.npc.VillagerType.PLAINS, net.minecraft.world.entity.npc.VillagerType.DESERT, net.minecraft.world.entity.npc.VillagerType.SNOW};
                for (int i = 0; i < types.length; i++) {
                    double x = ground.getX() + .5 + (i - 4) * 2, z = ground.getZ() + 4.5;
                    var doll = dev.physicalist.PhysicalistContent.PLAYER_RAGDOLL.get().create(level);
                    if (types[i] == EntityType.PLAYER) {
                        player.setYBodyRot(180);
                        doll.initialize(player, new net.minecraft.world.phys.Vec3(x, ground.getY() + 1.15, z));
                    } else {
                        var mob = types[i].create(level);
                        mob.moveTo(x, ground.getY() + .05, z, 180, 0);
                        if (mob instanceof net.minecraft.world.entity.LivingEntity living) living.yBodyRot = 180;
                        if (mob instanceof net.minecraft.world.entity.npc.Villager villager)
                            villager.setVillagerData(new net.minecraft.world.entity.npc.VillagerData(biomes[i], professions[i], 2));
                        doll.initialize(mob);
                        mob.discard();
                    }
                    level.addFreshEntity(doll);
                }
                // A real farmer to compare the ragdolls' clothes with.
                var reference = EntityType.VILLAGER.create(level);
                reference.moveTo(ground.getX() + .5 + 5 * 2, ground.getY(), ground.getZ() + 4.5, 180, 0);
                reference.yBodyRot = reference.yHeadRot = 180;
                reference.setNoAi(true);
                reference.setVillagerData(new net.minecraft.world.entity.npc.VillagerData(net.minecraft.world.entity.npc.VillagerType.PLAINS, net.minecraft.world.entity.npc.VillagerProfession.FARMER, 2));
                level.addFreshEntity(reference);
            });
        }
        // A shove to the side halfway: active ragdolls step to catch themselves.
        if (ticks == 100) server.execute(() -> {
            for (var doll : server.overworld().getEntitiesOfClass(dev.physicalist.PlayerRagdollEntity.class,
                    server.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(20)))
                // They face the camera (-z): +z shoves them backwards.
                for (var limb : doll.limbs()) limb.velocity = limb.velocity.add(0, 0, .1);
        });
        if (shot < SHOTS.length && ticks == SHOTS[shot]) {
            Screenshot.grab(mc.gameDirectory, "physicalist-photo-" + shot + ".png", mc.getMainRenderTarget(), message -> {});
            shot++;
            if (shot == SHOTS.length) mc.stop();
        }
    }

    private RagdollPhotoBooth() {}
}
