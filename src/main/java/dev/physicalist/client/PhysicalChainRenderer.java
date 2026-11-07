package dev.physicalist.client;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.physicalist.PhysicalChainEntity;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
public final class PhysicalChainRenderer extends EntityRenderer<PhysicalChainEntity>{
 private final net.minecraft.client.renderer.block.BlockRenderDispatcher blocks;
 public PhysicalChainRenderer(EntityRendererProvider.Context context){super(context);blocks=context.getBlockRenderDispatcher();}
 @Override public ResourceLocation getTextureLocation(PhysicalChainEntity e){return TextureAtlas.LOCATION_BLOCKS;}
 @Override public void render(PhysicalChainEntity e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
  Vec3 origin=new Vec3(net.minecraft.util.Mth.lerp(partial,e.xOld,e.getX()),net.minecraft.util.Mth.lerp(partial,e.yOld,e.getY()),net.minecraft.util.Mth.lerp(partial,e.zOld,e.getZ()));
  Vec3[][] segments=e.visualSegments(partial);int n=segments.length;
  for(int i=0;i<n;i++){Vec3 from=segments[i][0],to=segments[i][1],p=from.add(to).scale(.5).subtract(origin);
   // Keep the simulated roll; only swing the link onto its drawn segment.
   var q=e.visualRotation(i,partial);Vec3 d=to.subtract(from);if(d.lengthSqr()>1e-8){var axis=q.transform(new org.joml.Vector3f(0,1,0));d=d.normalize();q=new org.joml.Quaternionf().rotationTo(axis.x,axis.y,axis.z,(float)d.x,(float)d.y,(float)d.z).mul(q);}
   pose.pushPose();pose.translate(p.x,p.y,p.z);pose.mulPose(q);if((i&1)==1)pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90));pose.scale(.35f,(float)(e.links().get(i).half.y*2),.35f);pose.translate(-.5,-.5,-.5);blocks.renderSingleBlock(Blocks.CHAIN.defaultBlockState(),pose,buffers,light,OverlayTexture.NO_OVERLAY);pose.popPose();}super.render(e,yaw,partial,pose,buffers,light);
 }
}
