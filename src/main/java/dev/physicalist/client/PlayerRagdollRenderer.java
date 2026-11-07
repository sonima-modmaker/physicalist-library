package dev.physicalist.client;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.physicalist.PlayerRagdollEntity;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.*;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Standard 64x64 player UVs, or the original mob's model cut into parts; each limb follows its own interpolated rigid pose. */
public final class PlayerRagdollRenderer extends EntityRenderer<PlayerRagdollEntity>{
 private final ModelPart[] limbs=new ModelPart[6];
 private final Map<PlayerRagdollEntity,MobView> views=new WeakHashMap<>();private final Set<PlayerRagdollEntity> broken=Collections.newSetFromMap(new WeakHashMap<>());
 public PlayerRagdollRenderer(EntityRendererProvider.Context context){super(context);shadowRadius=.4F;
  int[][] uv={{0,0},{16,16},{32,48},{40,16},{16,48},{0,16}};int[][] size={{8,8,8},{8,12,4},{4,12,4},{4,12,4},{4,12,4},{4,12,4}};
  for(int i=0;i<6;i++){var mesh=new MeshDefinition();var s=size[i];mesh.getRoot().addOrReplaceChild("part",CubeListBuilder.create().texOffs(uv[i][0],uv[i][1]).addBox(-s[0]/2F,-s[1]/2F,-s[2]/2F,s[0],s[1],s[2]),net.minecraft.client.model.geom.PartPose.ZERO);limbs[i]=LayerDefinition.create(mesh,64,64).bakeRoot().getChild("part");}
 }
 @Override public ResourceLocation getTextureLocation(PlayerRagdollEntity e){return Minecraft.getInstance().getSkinManager().getInsecureSkin(e.owner()).texture();}
 @Override public void render(PlayerRagdollEntity e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
  Vec3 anchor=new Vec3(net.minecraft.util.Mth.lerp(partial,e.xOld,e.getX()),net.minecraft.util.Mth.lerp(partial,e.yOld,e.getY()),net.minecraft.util.Mth.lerp(partial,e.zOld,e.getZ()));
  if(e.mobType()!=null&&!broken.contains(e)){var view=views.computeIfAbsent(e,MobView::create);
   // A modded model that cannot be drawn in parts falls back to plain boxes instead of crashing the client.
   try{if(view!=null&&view.render(e,partial,anchor,pose,buffers,light)){super.render(e,yaw,partial,pose,buffers,light);return;}}
   catch(RuntimeException error){broken.add(e);org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Cannot render the ragdoll of {}",e.mobType(),error);}}
  var consumer=buffers.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(e)));
  for(int i=0;i<6;i++){Vec3 pos=e.renderPosition(i,partial).subtract(anchor);pose.pushPose();pose.translate(pos.x,pos.y,pos.z);pose.mulPose(e.renderRotation(i,partial));pose.scale(1,-1,-1);limbs[i].render(pose,consumer,light,OverlayTexture.NO_OVERLAY);pose.popPose();}
  super.render(e,yaw,partial,pose,buffers,light);
 }

 /**
  * The mob's own model in its rest pose. Each top-level part goes to one ragdoll limb (by name, else the nearest
  * limb) and moves rigidly with it; each limb's parts are centred on its physics box. A model that cannot be
  * split is drawn whole on the torso.
  */
 private record MobView(Entity dummy,EntityModel<Entity> model,ResourceLocation texture,List<ModelPart> parts,int[] limb,Vec3[] shift){
  @SuppressWarnings({"unchecked","rawtypes"})
  static MobView create(PlayerRagdollEntity doll){
   try{
    var mc=Minecraft.getInstance();Entity dummy=doll.mobType().create(doll.level());if(dummy==null)return null;
    try{dummy.load(doll.mobData());}catch(RuntimeException ignored){}
    if(!(mc.getEntityRenderDispatcher().getRenderer(dummy) instanceof LivingEntityRenderer renderer))return null;
    EntityModel<Entity> model=renderer.getModel();model.young=false;model.riding=false;model.attackTime=0;
    ResourceLocation texture=renderer.getTextureLocation(dummy);
    Map<ModelPart,String> names=new IdentityHashMap<>();List<ModelPart> top=new ArrayList<>();
    for(Class<?> c=model.getClass();c!=null&&c!=Object.class;c=c.getSuperclass())for(var f:c.getDeclaredFields())if(f.getType()==ModelPart.class&&!java.lang.reflect.Modifier.isStatic(f.getModifiers())){f.setAccessible(true);var part=(ModelPart)f.get(model);if(part!=null)names.putIfAbsent(part,f.getName());}
    if(model instanceof HierarchicalModel<?> h){var children=ModelPart.class.getDeclaredField("children");children.setAccessible(true);for(var entry:((Map<String,ModelPart>)children.get(h.root())).entrySet()){names.putIfAbsent(entry.getValue(),entry.getKey());top.add(entry.getValue());}}
    else if(model instanceof AgeableListModel<?>){for(String method:List.of("headParts","bodyParts")){var m=AgeableListModel.class.getDeclaredMethod(method);m.setAccessible(true);for(var part:(Iterable<ModelPart>)m.invoke(model))top.add(part);}}
    else if(model instanceof ListModel<?> list){for(var part:list.parts())top.add(part);}
    var rig=doll.rig();int n=top.size();int[] limb=new int[n];Vec3[] centre=new Vec3[n];double[] volume=new double[n];
    for(int i=0;i<n;i++){var part=top.get(i);part.getAllParts().forEach(ModelPart::resetPose);var bounds=bounds(part);if(bounds==null){limb[i]=-1;continue;}centre[i]=bounds[0];volume[i]=bounds[1].x*bounds[1].y*bounds[1].z;limb[i]=limbOf(names.getOrDefault(part,""),centre[i],rig);}
    Vec3[] sum=new Vec3[6];double[] weight=new double[6];for(int i=0;i<n;i++)if(limb[i]>=0){double w=Math.max(volume[i],1e-6);sum[limb[i]]=(sum[limb[i]]==null?Vec3.ZERO:sum[limb[i]]).add(centre[i].scale(w));weight[limb[i]]+=w;}
    Vec3[] shift=new Vec3[6];for(int l=0;l<6;l++)shift[l]=weight[l]>0?rig.center()[l].subtract(sum[l].scale(1/weight[l])):Vec3.ZERO;
    return new MobView(dummy,model,texture,top,limb,shift);
   }catch(ReflectiveOperationException|RuntimeException error){org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Cannot split the model of {} for its ragdoll",doll.mobType(),error);return null;}
  }
  /** Centre and size of a part with its children, in the rest frame (feet at y=0, facing +z). */
  private static Vec3[] bounds(ModelPart part){
   double[] box={Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
   part.visit(new PoseStack(),(pose,path,index,cube)->{for(int k=0;k<8;k++){var v=pose.pose().transformPosition(new Vector3f((k&1)==0?cube.minX:cube.maxX,(k&2)==0?cube.minY:cube.maxY,(k&4)==0?cube.minZ:cube.maxZ).div(16));Vec3 p=rest(v);box[0]=Math.min(box[0],p.x);box[1]=Math.min(box[1],p.y);box[2]=Math.min(box[2],p.z);box[3]=Math.max(box[3],p.x);box[4]=Math.max(box[4],p.y);box[5]=Math.max(box[5],p.z);}});
   if(box[0]>box[3])return null;return new Vec3[]{new Vec3((box[0]+box[3])/2,(box[1]+box[4])/2,(box[2]+box[5])/2),new Vec3(box[3]-box[0],box[4]-box[1],box[5]-box[2])};
  }
  /** Model space (pixels/16, y down, head towards -z) to the ragdoll rest frame, as LivingEntityRenderer does at yaw 0. */
  private static Vec3 rest(Vector3f m){return new Vec3(m.x,1.501-m.y,-m.z);}
  private static int limbOf(String name,Vec3 centre,PlayerRagdollEntity.Rig rig){
   String n=name.toLowerCase(Locale.ROOT);boolean right=centre.x<0,sided=Math.abs(centre.x)>.03;
   if(n.contains("head")||n.contains("neck"))return 0;
   // A centred "arms" part (villager's crossed arms) belongs to no side: it falls through to the nearest limb.
   if(sided&&(n.contains("arm")||(n.contains("leg")&&n.contains("front"))))return right?2:3;
   if(sided&&n.contains("leg"))return right?4:5;
   int best=1;double nearest=Double.MAX_VALUE;
   for(int l=0;l<6;l++){var h=rig.half()[l];var d=centre.subtract(rig.center()[l]);double dist=new Vec3(Math.max(0,Math.abs(d.x)-h.x),Math.max(0,Math.abs(d.y)-h.y),Math.max(0,Math.abs(d.z)-h.z)).length();if(dist<nearest-1e-9){nearest=dist;best=l;}}
   return best;
  }
  boolean render(PlayerRagdollEntity e,float partial,Vec3 anchor,PoseStack pose,MultiBufferSource buffers,int light){
   var consumer=buffers.getBuffer(model.renderType(texture));var rig=e.rig();
   if(parts.isEmpty()){draw(e,1,partial,anchor,pose,()->{model.renderToBuffer(pose,consumer,light,OverlayTexture.NO_OVERLAY,-1);},rig.center()[1],Vec3.ZERO);return true;}
   for(int i=0;i<parts.size();i++){if(limb[i]<0)continue;var part=parts.get(i);part.getAllParts().forEach(ModelPart::resetPose);draw(e,limb[i],partial,anchor,pose,()->part.render(pose,consumer,light,OverlayTexture.NO_OVERLAY),rig.center()[limb[i]],shift[limb[i]]);}
   return true;
  }
  /** Limb pose, then the rest frame about that limb's rest centre, then model space. */
  private static void draw(PlayerRagdollEntity e,int limb,float partial,Vec3 anchor,PoseStack pose,Runnable body,Vec3 centre,Vec3 shift){
   Vec3 pos=e.renderPosition(limb,partial).subtract(anchor);Vec3 back=shift.subtract(centre);
   pose.pushPose();pose.translate(pos.x,pos.y,pos.z);pose.mulPose(e.renderRotation(limb,partial));pose.translate(back.x,back.y,back.z);pose.scale(1,-1,-1);pose.translate(0,-1.501,0);body.run();pose.popPose();
  }
 }
}
