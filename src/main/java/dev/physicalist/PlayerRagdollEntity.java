package dev.physicalist;

import java.util.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.*;
import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;
import org.joml.Quaternionf;

/** Nonliving test dummy of a player or any mob: six independently rotating boxes joined by elastic sockets. */
public final class PlayerRagdollEntity extends Entity implements PhysicalistBodyProvider,PhysicalistDragTarget {
 private static final EntityDataAccessor<CompoundTag> MOB=SynchedEntityData.defineId(PlayerRagdollEntity.class,EntityDataSerializers.COMPOUND_TAG);
 private static final EntityDataAccessor<CompoundTag> POSE=SynchedEntityData.defineId(PlayerRagdollEntity.class,EntityDataSerializers.COMPOUND_TAG);
 /**
  * Rest layout facing +z with the feet at y=0: 0 head, 1 torso, 2/3 arms or front legs (-x/+x), 4/5 legs or hind
  * legs. Every limb hangs off the torso; twist turns a limb about its own (local y) axis, swing is the cone.
  */
 public record Rig(Vec3[] half,Vec3[] center,double[] mass,Vec3[] torsoAnchor,Vec3[] limbAnchor,double[] twist,double[] swing,boolean upright){
  private static Vec3 v(double x,double y,double z){return new Vec3(x,y,z);}
  public static final Rig PLAYER=new Rig(
   new Vec3[]{v(.25,.25,.25),v(.25,.375,.125),v(.125,.375,.125),v(.125,.375,.125),v(.125,.375,.125),v(.125,.375,.125)},
   new Vec3[]{v(0,1.78,0),v(0,1.125,0),v(-.4,1.125,0),v(.4,1.125,0),v(-.14,.345,0),v(.14,.345,0)},new double[]{1.5,4,1,1,1,1},
   new Vec3[]{v(0,.375,0),Vec3.ZERO,v(-.25,.3,0),v(.25,.3,0),v(-.14,-.375,0),v(.14,-.375,0)},
   new Vec3[]{v(0,-.28,0),Vec3.ZERO,v(.15,.3,0),v(-.15,.3,0),v(0,.405,0),v(0,.405,0)},
   new double[]{.9,0,.9,.9,.5,.5},new double[]{1.13,0,2.53,2.53,1.4,1.4},true);
  /** Upright mobs (zombies, villagers, golems): the player layout stretched to the mob's box. */
  static Rig humanoid(double width,double height){
   double sx=width/.6,sy=height/1.8,mass=Math.clamp(sx*sx*sy,.15,12);Vec3 scale=v(sx,sy,sx);
   Vec3[][] parts={PLAYER.half,PLAYER.center,PLAYER.torsoAnchor,PLAYER.limbAnchor};Vec3[][] out=new Vec3[4][6];
   for(int k=0;k<4;k++)for(int i=0;i<6;i++)out[k][i]=parts[k][i].multiply(scale);
   double[] masses=PLAYER.mass.clone();for(int i=0;i<6;i++)masses[i]*=mass;return new Rig(out[0],out[1],masses,out[2],out[3],PLAYER.twist,PLAYER.swing,true);
  }
  /** Animals: a horizontal torso on four legs with the head in front. */
  static Rig quadruped(double width,double height){
   double legs=Math.clamp(height*.45,.12,2),body=Math.max(.15,(height-legs)*.85),length=width*1.1,side=width*.36,leg=Math.clamp(width*.12,.04,.35),head=Math.clamp(width*.28,.08,.5);
   double mass=Math.clamp(width*width*height/(.6*.6*1.8),.1,25);Vec3 torso=v(0,legs+body/2,0);
   Vec3[] half=new Vec3[6],center=new Vec3[6],torsoAnchor=new Vec3[6],limbAnchor=new Vec3[6];
   half[1]=v(side,body/2,length/2);center[1]=torso;torsoAnchor[1]=limbAnchor[1]=Vec3.ZERO;
   half[0]=v(head,head,head);torsoAnchor[0]=v(0,body*.3,length/2);limbAnchor[0]=v(0,-head*.3,-head);center[0]=torso.add(torsoAnchor[0]).subtract(limbAnchor[0]).add(0,0,.02);
   for(int i=2;i<6;i++){double x=(i%2==0?-1:1)*(side-leg),z=(i<4?1:-1)*(length/2-leg*1.5);// A gap below the torso, as at the player's hips: a leg flush with it hit the torso as soon as it tilted and held the animal up.
    double gap=Math.min(.05,legs*.15);half[i]=v(leg,(legs-gap)/2,leg);center[i]=v(x,(legs-gap)/2,z);torsoAnchor[i]=v(x,-body/2,z);limbAnchor[i]=v(0,(legs-gap)/2+gap,0);}
   return new Rig(half,center,new double[]{mass*.12,mass*.6,mass*.07,mass*.07,mass*.07,mass*.07},torsoAnchor,limbAnchor,new double[]{.6,0,.3,.3,.3,.3},new double[]{.8,0,1.1,1.1,1.1,1.1},false);
  }
  static Rig of(double width,double height){return height>=width*1.6?humanoid(width,height):quadruped(width,height);}
 }
 private static final double[] COMPLIANCE={.00005,0,.00008,.00008,.00003,.00003},DAMPING={.25,0,.22,.22,.28,.28};
 private final List<RigidBodyState> parts=new ArrayList<>();private final List<ElasticJoint> joints=new ArrayList<>();
 private Vec3[] previous=new Vec3[6],target=new Vec3[6];private Quaternionf[] previousQ=new Quaternionf[6],targetQ=new Quaternionf[6];
 private GameProfile owner=new GameProfile(new UUID(0,0),"Ragdoll");private boolean ready,sleeping;private int quiet,age,lerp;private boolean pausedForChunks;
 private Rig rig=Rig.PLAYER;private CompoundTag mob=new CompoundTag();
 public PlayerRagdollEntity(EntityType<? extends PlayerRagdollEntity> type,Level level){super(type,level);noPhysics=true;noCulling=true;setNoGravity(true);}
 @Override protected void defineSynchedData(SynchedEntityData.Builder b){b.define(MOB,new CompoundTag());b.define(POSE,new CompoundTag());}
 public List<RigidBodyState> limbs(){ensure();return Collections.unmodifiableList(parts);}
 public GameProfile owner(){return owner;}
 public Rig rig(){return rig;}
 /** Entity type this is a ragdoll of, or null for a player ragdoll. */
 public EntityType<?> mobType(){var key=net.minecraft.resources.ResourceLocation.tryParse(mob.getString("Type"));return key==null||!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(key)?null:net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(key);}
 /** Saved data of the original mob (variant, colour, profession), for rendering it. */
 public CompoundTag mobData(){return mob.getCompound("Data");}
 public boolean sleeping(){return sleeping;}
 public boolean pausedForChunks(){return pausedForChunks;}
 public void initialize(Player player,Vec3 at){owner=player.getGameProfile();setPos(at);ensure();for(var p:parts){p.velocity=player.getDeltaMovement();p.angular=Vec3.ZERO;}publish();}
 /** Ragdoll copy of any entity, standing where it stands and facing where it faces. */
 public void initialize(Entity source){
  var tag=new CompoundTag();tag.putString("Type",EntityType.getKey(source.getType()).toString());tag.putDouble("W",source.getBbWidth());tag.putDouble("H",source.getBbHeight());
  var data=source.saveWithoutId(new CompoundTag());for(String key:List.of("Pos","Motion","Rotation","UUID","Passengers","Brain","Inventory","Items","ArmorItems","HandItems","Attributes","ActiveEffects","Leash","Offers","Gossips"))data.remove(key);
  if(data.sizeInBytes()<8192)tag.put("Data",data);setMob(tag);
  setPos(source.position().add(0,rig.center[1].y,0));ensure();
  // A ragdoll built in its exact rest pose balances like a statue: two legs only until a wobble puts it to sleep, four
  // flat feet for good. It starts tipping over sideways (about its facing axis); an animal already lies half on its side.
  var random=level().getRandom();float side=random.nextBoolean()?1:-1;
  var turn=new Quaternionf().rotationY((float)Math.toRadians(-(source instanceof LivingEntity living?living.yBodyRot:source.getYRot())));if(!rig.upright())turn.rotateZ(side*1.2F);
  var f=turn.transform(new org.joml.Vector3f(0,0,1));Vec3 roll=new Vec3(f.x,f.y,f.z).scale(side*.07),c=parts.get(1).position;double lowest=Double.MAX_VALUE;
  for(var p:parts){var d=turn.transform(new org.joml.Vector3f((float)(p.position.x-c.x),(float)(p.position.y-c.y),(float)(p.position.z-c.z)));p.position=c.add(d.x,d.y,d.z);p.orientation.set(turn);p.velocity=source.getDeltaMovement();p.angular=roll.add(new Vec3(random.nextGaussian(),random.nextGaussian()*.2,random.nextGaussian()).scale(.03));lowest=Math.min(lowest,CompoundCollision.bounds(p.box()).minY);}
  double lift=source.getY()+.02-lowest;for(var p:parts)p.position=p.position.add(0,lift,0);
  snapshot();refresh();publish();
 }
 private void setMob(CompoundTag tag){mob=tag;rig=tag.contains("Type")?Rig.of(Math.clamp(tag.getDouble("W"),.1,6),Math.clamp(tag.getDouble("H"),.1,6)):Rig.PLAYER;if(ready){ready=false;parts.clear();joints.clear();}if(!level().isClientSide)entityData.set(MOB,tag);}
 private void ensure(){if(ready)return;ready=true;Vec3 torso=rig.center[1];for(int i=0;i<6;i++)parts.add(new RigidBodyState(position().add(rig.center[i].subtract(torso)),rig.half[i],rig.mass[i]));
  for(int i=0;i<6;i++)if(i!=1)joints.add(ElasticJoint.socket(parts.get(1),parts.get(i),rig.torsoAnchor[i],rig.limbAnchor[i],COMPLIANCE[i],DAMPING[i]).limit(new Vec3(0,1,0),new Vec3(0,1,0),rig.swing[i]));
  snapshot();
 }
 private void snapshot(){for(int i=0;i<6;i++){previous[i]=parts.get(i).position;previousQ[i]=new Quaternionf(parts.get(i).orientation);}}
 public Vec3 renderPosition(int i,float partial){ensure();return previous[i].lerp(parts.get(i).position,partial);}
 public Quaternionf renderRotation(int i,float partial){ensure();return new Quaternionf(previousQ[i]).slerp(parts.get(i).orientation,partial);}
 @Override public void tick(){super.tick();ensure();snapshot();
  if(level().isClientSide){if(lerp>0){double alpha=1.0/lerp--;for(int i=0;i<6;i++){parts.get(i).position=parts.get(i).position.lerp(target[i],alpha);parts.get(i).orientation.slerp(targetQ[i],(float)alpha);}}refresh();return;}
  if(++age>=12000){discard();return;}
  if(getDeltaMovement().lengthSqr()>1e-8){for(var p:parts)p.velocity=p.velocity.add(getDeltaMovement());setDeltaMovement(Vec3.ZERO);sleeping=false;quiet=0;}
  if(dev.physicalist.nativecore.NativeWorlds.enqueue(this))return;
  if(sleeping&&PhysicalistExternalCollisions.available()&&tickCount%5==0&&!PhysicalistExternalCollisions.gather((ServerLevel)level(),this,getBoundingBox().inflate(.1)).isEmpty()){sleeping=false;quiet=0;}
  if(sleeping){if(tickCount%10!=0||supported()){refresh();return;}sleeping=false;quiet=0;}
  long timer=PhysicsMetrics.begin();try{simulate();}finally{PhysicsMetrics.end(timer);}refresh();if(tickCount%2==0)publish();
 }
 /** Resolve invalid overlapping spawn poses without turning depenetration into velocity. */
 public void relaxInitialSelfContacts(){ensure();boolean overlap=false;for(int a=0;a<6;a++)for(int b=a+1;b<6;b++){var c=RagdollSelfCollision.contact(parts,a,b,0);if(c!=null&&c.depth()>.01)overlap=true;}if(!overlap)return;for(var joint:joints)joint.beginStep();for(int i=0;i<96;i++){for(var joint:joints)joint.solve(1);RagdollSelfCollision.project(parts);}refresh();}
 public void nativeStepFinished(boolean asleep){sleeping=asleep;refresh();if(tickCount%2==0||asleep)publish();}
 private boolean supported(){var l=(ServerLevel)level();for(var p:parts)if(l.getBlockCollisions(this,CompoundCollision.bounds(p.box()).move(0,-.025,0)).iterator().hasNext())return true;return false;}
 private void simulate(){
  var level=(ServerLevel)level();AABB route=CompoundCollision.bounds(parts.getFirst().box());double maxSpeed=0;for(var p:parts){route=route.minmax(CompoundCollision.bounds(p.box()).expandTowards(p.velocity));maxSpeed=Math.max(maxSpeed,p.velocity.length());}
  route=route.inflate(.5);pausedForChunks=!level.hasChunksAt(net.minecraft.core.BlockPos.containing(route.minX,route.minY,route.minZ),net.minecraft.core.BlockPos.containing(route.maxX,route.maxY,route.maxZ));if(pausedForChunks)return;
  List<CompoundCollision.Box> obstacles=new ArrayList<>();for(var shape:level.getBlockCollisions(this,route))for(var box:shape.toAabbs())obstacles.add(CompoundCollision.box(box));
  var external=PhysicalistExternalCollisions.gather(level,this,route);for(var collider:external)obstacles.add(collider.box());
  var index=new CollisionIndex<>(obstacles,CompoundCollision::bounds);
  int substeps=Math.clamp((int)Math.ceil(maxSpeed/.1),4,16);double dt=1.0/substeps;boolean touched=false;
  for(int step=0;step<substeps;step++){
   for(var p:parts)p.integrate(dt,new Vec3(0,-PhysicalistConfig.defaults().gravity(),0));
   for(var joint:joints)joint.beginStep();
   for(int iteration=0;iteration<16;iteration++){
    for(var joint:joints)joint.solve(dt);
    RagdollSelfCollision.project(parts);
    for(var p:parts){var box=p.box();var candidates=index.query(CompoundCollision.bounds(box).inflate(.002));PhysicsMetrics.query(candidates.size());
     for(var obstacle:candidates){var c=CompoundCollision.contact(box,obstacle);if(c==null)continue;touched=true;PhysicsMetrics.contact();p.position=p.position.add(c.normal().scale(Math.min(.2,c.depth()+.0001)));box=p.box();}
    }
   }
   // Split positional projection: only actual force/contact impulses change velocity.
   for(int i=0;i<6;i++){var p=parts.get(i);p.velocity=p.velocity.scale(.997);p.angular=p.angular.scale(.99);
    if(p.velocity.length()>3)p.velocity=p.velocity.normalize().scale(3);if(p.angular.length()>1)p.angular=p.angular.normalize();

   }
   for(int iteration=0;iteration<8;iteration++){
    for(var joint:joints)joint.solveVelocity(1);
    RagdollSelfCollision.velocities(parts);
    worldVelocity(index,external);
   }
  }
  double energy=0;for(var p:parts)energy+=p.velocity.lengthSqr()+p.angular.lengthSqr()*.03;
  if(touched&&external.isEmpty()&&energy<.007){if(++quiet>=40){sleeping=true;for(var p:parts){p.velocity=Vec3.ZERO;p.angular=Vec3.ZERO;}publish();}}else quiet=0;
 }
 private void worldVelocity(CollisionIndex<CompoundCollision.Box> index,java.util.List<PhysicalistExternalCollisions.Collider> external){
  for(var p:parts){
    var box=p.box();for(var obstacle:index.query(CompoundCollision.bounds(box).inflate(.003))){var hit=CompoundCollision.contact(new CompoundCollision.Box(box.center(),box.axes(),new double[]{box.half()[0]+.002,box.half()[1]+.002,box.half()[2]+.002}),obstacle);if(hit==null)continue;
     Vec3 arm=hit.point().subtract(p.position),n=hit.normal();Vec3 shipVelocity=Vec3.ZERO;PhysicalistExternalCollisions.Collider ext=null;
     for(var candidate:external)if(candidate.box()==obstacle){ext=candidate;shipVelocity=candidate.velocity();break;}
     double closing=p.pointVelocity(hit.point()).subtract(shipVelocity).dot(n),weight=p.inverseMass+arm.cross(n).dot(p.inverseInertia(arm.cross(n)));
     if(closing<0&&weight>0){Vec3 impulse=n.scale(-closing/weight);p.impulse(impulse,hit.point());if(ext!=null&&ext.reaction()!=null)ext.reaction().accept(hit.point(),impulse);}
     Vec3 tangent=p.velocity.subtract(shipVelocity);tangent=tangent.subtract(n.scale(tangent.dot(n)));p.velocity=p.velocity.subtract(tangent.scale(.02));p.angular=p.angular.scale(.99);
    }
  }
 }
 private void refresh(){Vec3 center=parts.get(1).position;setPos(center);AABB bounds=CompoundCollision.bounds(parts.getFirst().box());for(var p:parts)bounds=bounds.minmax(CompoundCollision.bounds(p.box()));setBoundingBox(bounds.inflate(.02));}
 private CompoundTag saveOwner(){var tag=new CompoundTag();tag.putUUID("Id",owner.getId());tag.putString("Name",owner.getName());var textures=new ListTag();for(var property:owner.getProperties().get("textures")){var p=new CompoundTag();p.putString("Value",property.value());if(property.signature()!=null)p.putString("Signature",property.signature());textures.add(p);}tag.put("Textures",textures);return tag;}
 private void readOwner(CompoundTag tag){if(!tag.hasUUID("Id"))return;String name=tag.getString("Name");owner=new GameProfile(tag.getUUID("Id"),name.isBlank()?"Ragdoll":name.substring(0,Math.min(16,name.length())));var textures=tag.getList("Textures",Tag.TAG_COMPOUND);for(int i=0;i<Math.min(4,textures.size());i++){var p=textures.getCompound(i);if(p.getString("Value").length()<=16384)owner.getProperties().put("textures",new com.mojang.authlib.properties.Property("textures",p.getString("Value"),p.contains("Signature")?p.getString("Signature"):null));}}
 private CompoundTag state(){var tag=new CompoundTag();tag.put("Owner",saveOwner());var list=new ListTag();for(var p:parts){var t=new CompoundTag();t.putDouble("X",p.position.x-getX());t.putDouble("Y",p.position.y-getY());t.putDouble("Z",p.position.z-getZ());t.putFloat("QX",p.orientation.x);t.putFloat("QY",p.orientation.y);t.putFloat("QZ",p.orientation.z);t.putFloat("QW",p.orientation.w);t.putDouble("VX",p.velocity.x);t.putDouble("VY",p.velocity.y);t.putDouble("VZ",p.velocity.z);t.putDouble("AX",p.angular.x);t.putDouble("AY",p.angular.y);t.putDouble("AZ",p.angular.z);list.add(t);}tag.put("Parts",list);tag.putInt("Age",age);return tag;}
 private void publish(){entityData.set(POSE,state());}
 private void readState(CompoundTag tag,boolean client){ensure();readOwner(tag.getCompound("Owner"));var list=tag.getList("Parts",Tag.TAG_COMPOUND);if(list.size()!=6)return;age=Math.clamp(tag.getInt("Age"),0,12000);
  boolean first=target[0]==null;for(int i=0;i<6;i++){var t=list.getCompound(i);Vec3 pos=position().add(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z"));Quaternionf q=new Quaternionf(t.getFloat("QX"),t.getFloat("QY"),t.getFloat("QZ"),t.getFloat("QW"));if(!RigidBodyState.finite(pos)||!q.isFinite()||q.lengthSquared()<.5)continue;
   target[i]=pos;targetQ[i]=q.normalize();var p=parts.get(i);if(!client||first){p.position=pos;p.orientation.set(targetQ[i]);}p.velocity=limited(new Vec3(t.getDouble("VX"),t.getDouble("VY"),t.getDouble("VZ")),3);p.angular=limited(new Vec3(t.getDouble("AX"),t.getDouble("AY"),t.getDouble("AZ")),1);
  }lerp=3;if(first)snapshot();refresh();
 }
 private static Vec3 limited(Vec3 v,double cap){if(!RigidBodyState.finite(v))return Vec3.ZERO;return v.length()>cap?v.normalize().scale(cap):v;}
 @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key){super.onSyncedDataUpdated(key);if(!level().isClientSide)return;if(MOB.equals(key))setMob(entityData.get(MOB));if(POSE.equals(key))readState(entityData.get(POSE),true);}
 @Override protected void addAdditionalSaveData(CompoundTag tag){ensure();tag.put("Ragdoll",state());tag.put("Mob",mob);}
 @Override protected void readAdditionalSaveData(CompoundTag tag){setMob(tag.getCompound("Mob"));readState(tag.getCompound("Ragdoll"),false);publish();}
 @Override public boolean isPickable(){return true;}
 @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source,float amount){if(!level().isClientSide){Vec3 direction=source.getEntity()==null?new Vec3(0,.1,0):position().subtract(source.getEntity().position()).normalize().scale(.15);for(var p:parts)p.velocity=p.velocity.add(direction);sleeping=false;quiet=0;}return true;}
 @Override public void physicalistDrag(Player player,Vec3 goal){ensure();Vec3 shift=GrabController.velocity(goal.subtract(parts.get(1).position),parts.get(1).velocity,physicalistBody().profile().gravity()).subtract(parts.get(1).velocity);for(var p:parts)p.velocity=p.velocity.add(shift);sleeping=false;quiet=0;}
 private final PhysicsBody adapter=new PhysicsBody(){
  public Entity entity(){return PlayerRagdollEntity.this;}public List<CompoundCollision.Box> collisionBoxes(){ensure();return parts.stream().map(RigidBodyState::box).toList();}
  public Vec3 forward(){return new Vec3(0,0,1);}public Vec3 up(){return new Vec3(0,1,0);}public Vec3 angularVelocity(){ensure();return parts.get(1).angular;}
  public void setAngularVelocity(Vec3 angular){ensure();for(var p:parts)p.angular=angular;}
  public void rotate(Vec3 angular,double fraction){ensure();Vec3 center=parts.get(1).position;Quaternionf q=new Quaternionf().rotateXYZ((float)(angular.x*fraction),(float)(angular.y*fraction),(float)(angular.z*fraction));for(var p:parts){var v=q.transform(new org.joml.Vector3f((float)(p.position.x-center.x),(float)(p.position.y-center.y),(float)(p.position.z-center.z)));p.position=center.add(v.x,v.y,v.z);p.orientation.premul(q).normalize();}sleeping=false;quiet=0;refresh();publish();}
  public double aerodynamicMass(){double total=0;for(double m:rig.mass)total+=m;return total;}
 };
 @Override public PhysicsBody physicalistBody(){return adapter;}
}
