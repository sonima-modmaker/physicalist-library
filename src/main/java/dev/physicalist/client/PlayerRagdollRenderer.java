package dev.physicalist.client;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.physicalist.PlayerRagdollEntity;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.*;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Players: the 64x64 skin with its outer layer (hat, jacket, sleeves, trousers) and slim or wide arms. Mobs: the
 * mob's own model and every render layer that draws on it (villager biome and profession clothes, collars, wool,
 * eyes...), cut into the ragdoll's limbs. Each limb follows its own interpolated rigid pose.
 */
public final class PlayerRagdollRenderer extends EntityRenderer<PlayerRagdollEntity>{
 private final ModelPart[][] player=new ModelPart[2][6];
 private final Map<PlayerRagdollEntity,MobView> views=new WeakHashMap<>();private final Set<PlayerRagdollEntity> broken=Collections.newSetFromMap(new WeakHashMap<>());
 public PlayerRagdollRenderer(EntityRendererProvider.Context context){super(context);shadowRadius=.4F;
  // Order: head, torso, -x arm (skin's left arm), +x arm, -x leg, +x leg; {base u,v, overlay u,v, inflate}.
  int[][] uv={{0,0,32,0},{16,16,16,32},{32,48,48,48},{40,16,40,32},{16,48,0,48},{0,16,0,32}};float[] grow={.5F,.25F,.25F,.25F,.25F,.25F};
  for(int slim=0;slim<2;slim++)for(int i=0;i<6;i++){
   int[] s=switch(i){case 0->new int[]{8,8,8};case 1->new int[]{8,12,4};case 2,3->new int[]{slim==1?3:4,12,4};default->new int[]{4,12,4};};
   var mesh=new MeshDefinition();var cubes=CubeListBuilder.create().texOffs(uv[i][0],uv[i][1]).addBox(-s[0]/2F,-s[1]/2F,-s[2]/2F,s[0],s[1],s[2])
    .texOffs(uv[i][2],uv[i][3]).addBox(-s[0]/2F,-s[1]/2F,-s[2]/2F,s[0],s[1],s[2],new CubeDeformation(grow[i]));
   mesh.getRoot().addOrReplaceChild("part",cubes,net.minecraft.client.model.geom.PartPose.ZERO);player[slim][i]=LayerDefinition.create(mesh,64,64).bakeRoot().getChild("part");
  }
 }
 private PlayerSkin skin(PlayerRagdollEntity e){return Minecraft.getInstance().getSkinManager().getInsecureSkin(e.owner());}
 @Override public ResourceLocation getTextureLocation(PlayerRagdollEntity e){return skin(e).texture();}
 @Override public void render(PlayerRagdollEntity e,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
  Vec3 anchor=new Vec3(net.minecraft.util.Mth.lerp(partial,e.xOld,e.getX()),net.minecraft.util.Mth.lerp(partial,e.yOld,e.getY()),net.minecraft.util.Mth.lerp(partial,e.zOld,e.getZ()));
  if(e.mobType()!=null&&!broken.contains(e)){var view=views.computeIfAbsent(e,MobView::create);
   // A modded model that cannot be drawn in parts falls back to plain boxes instead of crashing the client.
   try{if(view!=null&&view.render(e,partial,anchor,pose,buffers,light)){super.render(e,yaw,partial,pose,buffers,light);return;}}
   catch(RuntimeException|LinkageError error){broken.add(e);org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Cannot render the ragdoll of {}",e.mobType(),error);}}
  var skin=skin(e);var consumer=buffers.getBuffer(RenderType.entityTranslucent(skin.texture()));var parts=player[skin.model()==PlayerSkin.Model.SLIM?1:0];
  for(int i=0;i<6;i++){Vec3 pos=e.renderPosition(i,partial).subtract(anchor);pose.pushPose();pose.translate(pos.x,pos.y,pos.z);pose.mulPose(e.renderRotation(i,partial));pose.scale(1,-1,-1);parts[i].render(pose,consumer,light,OverlayTexture.NO_OVERLAY);pose.popPose();}
  super.render(e,yaw,partial,pose,buffers,light);
 }

 /** Where one top-level model part goes: a whole limb, nowhere (-1), or its cubes split over the limbs. */
 private record Piece(ModelPart part,int limb,List<List<ModelPart.Cube>> split,boolean force){Piece(ModelPart part,int limb,List<List<ModelPart.Cube>> split){this(part,limb,split,false);}}
 /** A model the ragdoll draws through: the mob's own, or a layer's (wool, outer clothing). */
 private record Cut(EntityModel<?> model,List<Piece> pieces){}

 /**
  * The mob's model in its rest pose, drawn once per limb with only that limb's parts visible, so the mob's render
  * layers (which draw the same model with other textures) are cut the same way. Each top-level part goes to one
  * limb by name, else to the nearest limb; crossed arms (villagers, witches, wandering traders) are split into two
  * hanging arms. Layers that draw regardless of visibility (a carried block, a pumpkin head) are drawn with the head.
  */
 private record MobView(LivingEntity dummy,LivingEntityRenderer<LivingEntity,EntityModel<LivingEntity>> renderer,EntityModel<LivingEntity> model,ResourceLocation texture,List<Cut> cuts,List<RenderLayer<LivingEntity,EntityModel<LivingEntity>>> masked,List<RenderLayer<LivingEntity,EntityModel<LivingEntity>>> loose,Vec3[] shift,float size){
  private static Field cubes;
  /** The extra scale a mob's renderer draws it at (a slime's size, a ghast's or giant's bulk), 1 if unknown. */
  private static double rendererScale(LivingEntityRenderer<?,?> renderer,LivingEntity dummy){
   try{var m=LivingEntityRenderer.class.getDeclaredMethod("scale",LivingEntity.class,PoseStack.class,float.class);m.setAccessible(true);
    var pose=new PoseStack();m.invoke(renderer,dummy,pose,0F);var s=pose.last().pose().getScale(new Vector3f());double v=Math.cbrt(Math.abs(s.x*s.y*s.z));
    return Double.isFinite(v)&&v>.01?v:1;}
   catch(ReflectiveOperationException|RuntimeException|LinkageError error){return 1;}
  }
  @SuppressWarnings({"unchecked","rawtypes"})
  static MobView create(PlayerRagdollEntity doll){
   try{
    var mc=Minecraft.getInstance();Entity created=doll.mobType().create(doll.level());if(!(created instanceof LivingEntity dummy))return null;
    try{dummy.load(doll.mobData());}catch(RuntimeException ignored){}
    if(!(mc.getEntityRenderDispatcher().getRenderer(dummy) instanceof LivingEntityRenderer renderer))return null;
    EntityModel<LivingEntity> model=renderer.getModel();model.young=false;model.riding=false;model.attackTime=0;
    ResourceLocation texture=renderer.getTextureLocation(dummy);var rig=doll.rig();
    if(cubes==null){cubes=ModelPart.class.getDeclaredField("cubes");cubes.setAccessible(true);}
    Cut main=cut(model,rig);if(main==null)return null;List<Cut> cuts=new ArrayList<>();cuts.add(main);
    // Upright mobs share the ragdoll's rest frame (feet at y=0, player proportions), so their parts are drawn exactly
    // where the model puts them and stay joined. Centring each limb's parts on its box instead lifted a villager's
    // body by its long robe and left the legs hanging apart below it. Animals' rigs are made from their size only:
    // their parts are centred on the boxes.
    // Babies, slimes and other resized mobs: the model drawn at the size the mob was (its rig is built from its box).
    double size=dummy.getScale()*rendererScale(renderer,dummy);
    Vec3[] sum=new Vec3[6];double[] weight=new double[6];if(rig.upright())Arrays.fill(weight,-1);
    // Each animal limb hangs from its joint: the model's own pivot of the leg (hip) or head (neck) goes to the rig's
    // joint, and the body sits where its hips meet the rig's hips on average. Centring legs on their boxes left a gap
    // wherever the model's legs were shorter than the rig's, and the legs looked torn off.
    Vec3[] pivot=new Vec3[6];double[] biggest=new double[6];
    if(!rig.upright())for(var piece:main.pieces){for(int l=0;l<6;l++){var b=bounds(piece,l);if(b==null)continue;double w=Math.max(b[1].x*b[1].y*b[1].z,1e-6);sum[l]=(sum[l]==null?Vec3.ZERO:sum[l]).add(b[0].scale(w*size));weight[l]+=w;
     if(piece.split==null&&w>biggest[l]){var p=rest(new Vector3f(piece.part.x,piece.part.y,piece.part.z).div(16));var lo=b[0].subtract(b[1].scale(.5)).subtract(.07,.07,.07);var hi=b[0].add(b[1].scale(.5)).add(.07,.07,.07);
      if(p.x>=lo.x&&p.y>=lo.y&&p.z>=lo.z&&p.x<=hi.x&&p.y<=hi.y&&p.z<=hi.z){biggest[l]=w;pivot[l]=p.scale(size);}}}}
    Vec3[] shift=new Vec3[6];for(int l=0;l<6;l++)shift[l]=weight[l]>0?rig.center()[l].subtract(sum[l].scale(1/weight[l])):Vec3.ZERO;
    if(!rig.upright()){Vec3 hips=Vec3.ZERO;int n=0;for(int l=2;l<6;l++)if(pivot[l]!=null&&weight[l]>0){hips=hips.add(rig.center()[1].add(rig.torsoAnchor()[l]).subtract(pivot[l]));n++;}
     if(n>0&&weight[1]>0)shift[1]=hips.scale(1./n);
     for(int l=0;l<6;l++)if(l!=1&&pivot[l]!=null&&weight[l]>0)shift[l]=rig.center()[l].add(rig.limbAnchor()[l]).subtract(pivot[l]);}
    List<RenderLayer<LivingEntity,EntityModel<LivingEntity>>> masked=new ArrayList<>(),loose=new ArrayList<>();
    var layersField=LivingEntityRenderer.class.getDeclaredField("layers");layersField.setAccessible(true);
    for(var layer:(List<RenderLayer<LivingEntity,EntityModel<LivingEntity>>>)layersField.get(renderer)){
     for(Class<?> c=layer.getClass();c!=null&&c!=RenderLayer.class&&c!=Object.class;c=c.getSuperclass())for(var f:c.getDeclaredFields()){
      if(java.lang.reflect.Modifier.isStatic(f.getModifiers())||!EntityModel.class.isAssignableFrom(f.getType()))continue;f.setAccessible(true);var own=(EntityModel<?>)f.get(layer);
      if(own!=null&&own!=model&&cuts.stream().noneMatch(k->k.model==own)){var extra=cut(own,rig);if(extra!=null)cuts.add(extra);}
     }
    }
    var view=new MobView(dummy,renderer,model,texture,cuts,masked,loose,shift,(float)size);
    for(var layer:(List<RenderLayer<LivingEntity,EntityModel<LivingEntity>>>)layersField.get(renderer)){
     // Probe: a layer that still draws with every part hidden does not follow the limbs.
     var counter=new Counter();var saved=view.hideAll();
     try{layer.render(new PoseStack(),counter,0xF000F0,dummy,0,0,0,dummy.tickCount,0,0);(counter.count>0?loose:masked).add(layer);}
     catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger("Physicalist").debug("Ragdoll skips layer {} of {}",layer.getClass().getSimpleName(),doll.mobType(),error);}
     finally{view.restore(saved);}
    }
    return view;
   }catch(ReflectiveOperationException|RuntimeException|LinkageError error){org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Cannot split the model of {} for its ragdoll",doll.mobType(),error);return null;}
  }
  @SuppressWarnings({"unchecked","rawtypes"})
  private static Cut cut(EntityModel<?> model,PlayerRagdollEntity.Rig rig)throws ReflectiveOperationException{
   Map<ModelPart,String> names=new IdentityHashMap<>();List<ModelPart> top=new ArrayList<>();
   for(Class<?> c=model.getClass();c!=null&&c!=Object.class;c=c.getSuperclass())for(var f:c.getDeclaredFields())if(f.getType()==ModelPart.class&&!java.lang.reflect.Modifier.isStatic(f.getModifiers())){f.setAccessible(true);var part=(ModelPart)f.get(model);if(part!=null)names.putIfAbsent(part,f.getName());}
   if(model instanceof HierarchicalModel<?> h){var children=ModelPart.class.getDeclaredField("children");children.setAccessible(true);for(var entry:((Map<String,ModelPart>)children.get(h.root())).entrySet()){names.putIfAbsent(entry.getValue(),entry.getKey());top.add(entry.getValue());}}
   else if(model instanceof AgeableListModel<?>){for(String method:List.of("headParts","bodyParts")){var m=AgeableListModel.class.getDeclaredMethod(method);m.setAccessible(true);for(var part:(Iterable<ModelPart>)m.invoke(model))top.add(part);}}
   else if(model instanceof ListModel<?> list){for(var part:list.parts())top.add(part);}
   if(top.isEmpty())return null;
   boolean sidedArms=false,crossedArms=false;for(var part:top){part.resetPose();String n=names.getOrDefault(part,"").toLowerCase(Locale.ROOT);var b=bounds(part);if(b!=null&&n.contains("arm")){if(Math.abs(b[0].x)>.06)sidedArms=true;else crossedArms=true;}}
   List<Piece> pieces=new ArrayList<>();
   for(var part:top){
    part.getAllParts().forEach(ModelPart::resetPose);String name=names.getOrDefault(part,"");var b=bounds(part);if(b==null){pieces.add(new Piece(part,-1,null));continue;}
    boolean crossed=name.toLowerCase(Locale.ROOT).contains("arm")&&Math.abs(b[0].x)<=.06&&rig.upright();
    if(crossed&&sidedArms){pieces.add(new Piece(part,-1,null));continue;}// Illagers: the separate arms are drawn instead.
    if(crossed){var split=splitArms(part);if(split!=null){pieces.add(new Piece(part,-2,split));continue;}}
    // An illager hides its separate arms while they are crossed; the ragdoll shows them.
    boolean force=crossedArms&&name.toLowerCase(Locale.ROOT).contains("arm");
    pieces.add(new Piece(part,limbOf(name,b[0],rig),null,force));
   }
   return new Cut(model,pieces);
  }
  /**
   * Crossed arms are two sleeves either side and the hands across the middle. Each sleeve becomes an arm, hanging
   * straight, with its half of the hands below it. Null when the part does not look like that.
   */
  @SuppressWarnings("unchecked")
  private static List<List<ModelPart.Cube>> splitArms(ModelPart part)throws ReflectiveOperationException{
   var all=(List<ModelPart.Cube>)cubes.get(part);List<ModelPart.Cube> right=new ArrayList<>(),left=new ArrayList<>(),middle=new ArrayList<>();
   for(var c:all){float x=(c.minX+c.maxX)/2;(x<-1.5F?right:x>1.5F?left:middle).add(c);}
   if(right.isEmpty()||left.isEmpty())return null;
   for(var hands:middle){var r=right.getFirst();var l=left.getFirst();var a=half(hands,r,true);var b=half(hands,l,false);if(a!=null)right.add(a);if(b!=null)left.add(b);}
   List<List<ModelPart.Cube>> out=new ArrayList<>();for(int i=0;i<6;i++)out.add(List.of());out.set(2,List.copyOf(right));out.set(3,List.copyOf(left));return out;
  }
  /** Half of the hands' box, textured from its half of the hands, hung under a sleeve. */
  private static ModelPart.Cube half(ModelPart.Cube hands,ModelPart.Cube sleeve,boolean first){
   try{var polygons=ModelPart.Cube.class.getDeclaredField("polygons");polygons.setAccessible(true);
    float minU=Float.MAX_VALUE,maxU=-Float.MAX_VALUE,minV=Float.MAX_VALUE,maxV=-Float.MAX_VALUE;
    // Polygon and Vertex are package-private records: read their texture coordinates reflectively.
    for(Object polygon:(Object[])polygons.get(hands))for(Object vertex:(Object[])member(polygon,"vertices")){
     float uu=(float)member(vertex,"u"),vv=(float)member(vertex,"v");minU=Math.min(minU,uu);maxU=Math.max(maxU,uu);minV=Math.min(minV,vv);maxV=Math.max(maxV,vv);}
    float dx=hands.maxX-hands.minX,dy=hands.maxY-hands.minY,dz=hands.maxZ-hands.minZ;if(maxU<=minU||maxV<=minV||dx<2)return null;
    float texW=2*(dx+dz)/(maxU-minU),texH=(dz+dy)/(maxV-minV);int u=Math.round(minU*texW),v=Math.round(minV*texH);float w=sleeve.maxX-sleeve.minX;
    return new ModelPart.Cube(first?u:u+Math.round(dx/2),v,sleeve.minX,sleeve.maxY,sleeve.minZ,w,dy,sleeve.maxZ-sleeve.minZ,0,0,0,false,texW,texH,EnumSet.allOf(Direction.class));
   }catch(ReflectiveOperationException|RuntimeException error){return null;}
  }
  /** A record component or field by name (ModelPart's polygons and vertices are package-private). */
  private static Object member(Object owner,String name)throws ReflectiveOperationException{
   try{var m=owner.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(owner);}
   catch(NoSuchMethodException missing){var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
  }
  /** Rest-frame centre and size of what a piece draws on one limb, or null. */
  @SuppressWarnings("unchecked")
  private static Vec3[] bounds(Piece piece,int limb){
   if(piece.split==null)return piece.limb==limb?bounds(piece.part):null;
   if(piece.split.get(limb).isEmpty())return null;
   try{var saved=(List<ModelPart.Cube>)cubes.get(piece.part);cubes.set(piece.part,piece.split.get(limb));var pose=piece.part.storePose();straighten(piece.part);
    try{return bounds(piece.part);}finally{cubes.set(piece.part,saved);piece.part.loadPose(pose);}}
   catch(ReflectiveOperationException error){return null;}
  }
  /** A split arm hangs straight down from where the crossed arms pivot. */
  private static void straighten(ModelPart part){part.xRot=0;part.yRot=0;part.zRot=0;}
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
   if(n.contains("head")||n.contains("neck")||n.contains("hat")||n.contains("nose"))return 0;
   if(sided&&(n.contains("arm")||(n.contains("leg")&&n.contains("front"))))return right?2:3;
   if(sided&&n.contains("leg"))return right?4:5;
   int best=1;double nearest=Double.MAX_VALUE;
   for(int l=0;l<6;l++){var h=rig.half()[l];var d=centre.subtract(rig.center()[l]);double dist=new Vec3(Math.max(0,Math.abs(d.x)-h.x),Math.max(0,Math.abs(d.y)-h.y),Math.max(0,Math.abs(d.z)-h.z)).length();if(dist<nearest-1e-9){nearest=dist;best=l;}}
   return best;
  }

  /**
   * Everything the limb passes change, to put back for the mobs that share this model. Parts of other limbs are
   * hidden by taking away their cubes and children, not by their visible flag: layers set that flag themselves (the
   * villager's profession layer shows the head for its hat), which drew only hats and no clothes.
   */
  private record Saved(Map<ModelPart,Boolean> visible,Map<ModelPart,net.minecraft.client.model.geom.PartPose> pose,Map<ModelPart,List<ModelPart.Cube>> cubes,Map<ModelPart,Map<String,ModelPart>> children){}
  private static Field children;
  @SuppressWarnings("unchecked")
  private Saved save(){Map<ModelPart,Boolean> v=new IdentityHashMap<>();Map<ModelPart,net.minecraft.client.model.geom.PartPose> p=new IdentityHashMap<>();Map<ModelPart,List<ModelPart.Cube>> c=new IdentityHashMap<>();Map<ModelPart,Map<String,ModelPart>> k=new IdentityHashMap<>();
   try{if(children==null){children=ModelPart.class.getDeclaredField("children");children.setAccessible(true);}
    for(var cut:cuts)for(var piece:cut.pieces){var part=piece.part;v.put(part,part.visible);p.put(part,part.storePose());c.put(part,(List<ModelPart.Cube>)cubes.get(part));k.put(part,(Map<String,ModelPart>)children.get(part));}}
   catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
   return new Saved(v,p,c,k);}
  private static void set(ModelPart part,List<ModelPart.Cube> list,Map<String,ModelPart> kids){try{cubes.set(part,list);children.set(part,kids);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
  private void restore(Saved s){s.cubes.forEach((part,list)->set(part,list,s.children.get(part)));s.visible.forEach((part,v)->part.visible=v);s.pose.forEach(ModelPart::loadPose);}
  private Saved hideAll(){var s=save();for(var cut:cuts)for(var piece:cut.pieces)set(piece.part,List.of(),Map.of());return s;}
  /** Shows only what belongs to one limb, in its rest pose. */
  private boolean show(int limb,Saved saved){boolean any=false;
   for(var cut:cuts)for(var piece:cut.pieces){var part=piece.part;set(part,saved.cubes.get(part),saved.children.get(part));part.getAllParts().forEach(ModelPart::resetPose);part.visible=piece.force||saved.visible.get(part);
    if(piece.split!=null){var list=piece.split.get(limb);if(list.isEmpty())set(part,List.of(),Map.of());else{set(part,list,saved.children.get(part));straighten(part);any|=cut.model==model;}}
    else if(piece.limb==limb)any|=cut.model==model;
    else set(part,List.of(),Map.of());
   }return any;}

  boolean render(PlayerRagdollEntity e,float partial,Vec3 anchor,PoseStack pose,MultiBufferSource buffers,int light){
   var rig=e.rig();float age=dummy.tickCount+partial;var saved=save();
   try{
    for(int limb=0;limb<6;limb++){
     boolean any=show(limb,saved);if(!any&&limb!=0)continue;
     Vec3 pos=e.renderPosition(limb,partial).subtract(anchor);Vec3 back=shift[limb].subtract(rig.center()[limb]);
     pose.pushPose();pose.translate(pos.x,pos.y,pos.z);pose.mulPose(e.renderRotation(limb,partial));pose.translate(back.x,back.y,back.z);pose.scale(size,-size,-size);pose.translate(0,-1.501,0);
     if(any)model.renderToBuffer(pose,buffers.getBuffer(model.renderType(texture)),light,OverlayTexture.NO_OVERLAY,-1);
     if(any)for(var layer:masked)layer.render(pose,buffers,light,dummy,0,0,partial,age,0,0);
     if(limb==0){var hidden=hideAll();try{for(var layer:loose)layer.render(pose,buffers,light,dummy,0,0,partial,age,0,0);}finally{restore(hidden);}}
     pose.popPose();
    }
   }finally{restore(saved);}
   return true;
  }
 }

 /** Counts what a layer draws, to tell layers that follow the model's visibility from those that do not. */
 private static final class Counter implements MultiBufferSource,VertexConsumer{
  int count;
  @Override public VertexConsumer getBuffer(RenderType type){return this;}
  @Override public VertexConsumer addVertex(float x,float y,float z){count++;return this;}
  @Override public VertexConsumer setColor(int r,int g,int b,int a){return this;}
  @Override public VertexConsumer setUv(float u,float v){return this;}
  @Override public VertexConsumer setUv1(int u,int v){return this;}
  @Override public VertexConsumer setUv2(int u,int v){return this;}
  @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
 }
}
