package dev.physicalist.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.physicalist.soft.SoftBodyEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Draws a soft body's mesh where its nodes are, played back from its timeline of simulation steps. */
public final class SoftBodyRenderer extends EntityRenderer<SoftBodyEntity> {
    public SoftBodyRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius = 0; }

    @Override public ResourceLocation getTextureLocation(SoftBodyEntity e) { return TextureAtlas.LOCATION_BLOCKS; }
    @Override public boolean shouldRender(SoftBodyEntity e, Frustum frustum, double x, double y, double z) { return e.nodes() != null; }

    @Override
    public void render(SoftBodyEntity e, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        var view = SoftBodyView.of(e);
        if (view == null || e.nodes() == null) return;
        // The pose stack sits at the entity's interpolated position.
        view.render(e, partial, Mth.lerp(partial, e.xo, e.getX()), Mth.lerp(partial, e.yo, e.getY()), Mth.lerp(partial, e.zo, e.getZ()), pose, buffers, light);
    }
}
