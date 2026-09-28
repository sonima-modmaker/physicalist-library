package dev.physicalist.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.physicalist.CompoundCollision;
import dev.physicalist.PhysicalistBodyProvider;
import dev.physicalist.PhysicalistLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Draw the actual oriented compound collision boxes in green when F3+B is enabled. */
@EventBusSubscriber(modid = PhysicalistLibrary.MOD_ID, value = Dist.CLIENT)
public final class PhysicalistCollisionDebugRenderer {
    private static final int MAX_DEBUG_BOXES = 4096;

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.getEntityRenderDispatcher().shouldRenderHitBoxes()) return;
        var camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        var buffers = minecraft.renderBuffers().bufferSource();
        var consumer = buffers.getBuffer(RenderType.lines());
        int drawn = 0;
        for (var entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof PhysicalistBodyProvider provider) || !provider.physicalistRenderDebugCollision()) continue;
            var body = provider.physicalistBody();
            if (body == null) continue;
            for (CompoundCollision.Box box : body.collisionBoxes()) {
                if (++drawn > MAX_DEBUG_BOXES) break;
                var axes = box.axes();
                var half = box.half();
                if (axes.length != 3 || half.length != 3) continue;
                Matrix3f basis = new Matrix3f()
                        .setColumn(0, new Vector3f((float) axes[0].x, (float) axes[0].y, (float) axes[0].z))
                        .setColumn(1, new Vector3f((float) axes[1].x, (float) axes[1].y, (float) axes[1].z))
                        .setColumn(2, new Vector3f((float) axes[2].x, (float) axes[2].y, (float) axes[2].z));
                pose.pushPose();
                pose.translate(box.center().x - camera.x, box.center().y - camera.y, box.center().z - camera.z);
                pose.mulPose(basis.getNormalizedRotation(new Quaternionf()));
                LevelRenderer.renderLineBox(pose, consumer,
                        new AABB(-half[0], -half[1], -half[2], half[0], half[1], half[2]),
                        .1f, 1f, .25f, 1f);
                pose.popPose();
            }
            if (drawn >= MAX_DEBUG_BOXES) break;
        }
        if (drawn > 0) buffers.endBatch(RenderType.lines());
    }

    private PhysicalistCollisionDebugRenderer() {}
}
