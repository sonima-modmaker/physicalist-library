package dev.physicalist.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.physicalist.PhysicalBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class PhysicalBlockRenderer extends EntityRenderer<PhysicalBlockEntity> {
    private final BlockRenderDispatcher blocks;
    public PhysicalBlockRenderer(EntityRendererProvider.Context context) {
        super(context);
        blocks = context.getBlockRenderDispatcher();
    }

    @Override public ResourceLocation getTextureLocation(PhysicalBlockEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override public void render(PhysicalBlockEntity entity, float yaw, float partialTick,
                                 PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(Axis.XP.rotationDegrees(Mth.rotLerp(partialTick, entity.xRotO, entity.getXRot())));
        pose.mulPose(Axis.ZP.rotationDegrees(entity.visualRoll(partialTick)));
        float scale = (float) entity.physicalistScale();
        pose.scale(scale, scale, scale);
        pose.translate(-entity.localCenter().x, -entity.localCenter().y, -entity.localCenter().z);
        for (PhysicalBlockEntity.BlockPart part : entity.parts()) {
            pose.pushPose();
            pose.translate(part.offset().getX(), part.offset().getY(), part.offset().getZ());
            blocks.renderSingleBlock(part.state(), pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }
}
