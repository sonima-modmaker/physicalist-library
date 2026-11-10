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
 /** Ragdoll of a player at a point (torso centre), facing where the player faces. */
 public void initialize(Player player,Vec3 at){owner=player.getGameProfile();setPos(at);ensure();place(player.yBodyRot,false,player.getDeltaMovement(),Double.NaN);publish();}
 /** Ragdoll copy of any entity, standing where it stands and facing where it faces. */
 public void initialize(Entity source){initialize(source,false);}
 /**
  * Ragdoll copy of any entity, standing where it stands and facing where it faces. An active one stands up straight
  * and keeps its balance (see {@link #setActive}); a limp one starts tipping over.
  */
 public void initialize(Entity source,boolean active){
  if(source instanceof Player player){owner=player.getGameProfile();setPos(source.position().add(0,rig.center[1].y,0));ensure();}
  else{
   var tag=new CompoundTag();tag.putString("Type",EntityType.getKey(source.getType()).toString());tag.putDouble("W",source.getBbWidth());tag.putDouble("H",source.getBbHeight());
   var data=source.saveWithoutId(new CompoundTag());for(String key:List.of("Pos","Motion","Rotation","UUID","Passengers","Brain","Inventory","Items","ArmorItems","HandItems","Attributes","ActiveEffects","Leash","Offers","Gossips","HurtTime","DeathTime","Health","CustomName"))data.remove(key);
   if(data.sizeInBytes()<8192)tag.put("Data",data);setMob(tag);
   setPos(source.position().add(0,rig.center[1].y,0));ensure();
  }
  this.active=active;place(source instanceof LivingEntity living?living.yBodyRot:source.getYRot(),!active,source.getDeltaMovement(),source.getY());
  publish();
 }
 /**
  * Turns the rest pose to a facing and sets it on the ground (groundY, NaN keeps the height). A ragdoll built in its
  * exact rest pose balances like a statue: two legs only until a wobble puts it to sleep, four flat feet for good. A
  * limp one starts tipping over sideways (about its facing axis); an animal already lies half on its side.
  */
 private void place(float yaw,boolean tip,Vec3 velocity,double groundY){
  var random=level().getRandom();float side=random.nextBoolean()?1:-1;
  var turn=new Quaternionf().rotationY((float)Math.toRadians(-yaw));if(tip&&!rig.upright())turn.rotateZ(side*1.2F);
  var f=turn.transform(new org.joml.Vector3f(0,0,1));Vec3 roll=tip?new Vec3(f.x,f.y,f.z).scale(side*.07):Vec3.ZERO,c=parts.get(1).position;double lowest=Double.MAX_VALUE;
  for(var p:parts){var d=turn.transform(new org.joml.Vector3f((float)(p.position.x-c.x),(float)(p.position.y-c.y),(float)(p.position.z-c.z)));p.position=c.add(d.x,d.y,d.z);p.orientation.set(turn);p.velocity=velocity;p.angular=tip?roll.add(new Vec3(random.nextGaussian(),random.nextGaussian()*.2,random.nextGaussian()).scale(.03)):Vec3.ZERO;lowest=Math.min(lowest,CompoundCollision.bounds(p.box()).minY);}
  if(!Double.isNaN(groundY)){double lift=groundY+.02-lowest;for(var p:parts)p.position=p.position.add(0,lift,0);}
  snapshot();refresh();
 }

 private boolean active,fallen;private int stepLeg=-1,stepTicks;private Vec3 stepTarget=Vec3.ZERO;private double heading=Double.NaN;
 /** Whether this ragdoll tries to keep its balance. */
 public boolean active(){return active;}
 /** An active ragdoll that was pushed past recovery and now lies limp. */
 public boolean fallen(){return fallen;}
 /**
  * Active ragdolls (upright ones: players, zombies, villagers...) lean against a push and step under their centre of
  * mass to stay on their feet; once tipped past recovery they go limp. Turning it off makes the ragdoll limp at once.
  */
 public void setActive(boolean value){active=value;fallen=false;stepLeg=-1;stepTicks=0;heading=Double.NaN;sleeping=false;quiet=0;}
 private static final int STEP_TICKS=7;
 /**
  * Balance as a capture-point controller on the limbs' velocities (applied before each physics step): the torso and
  * head are turned upright, and when the point the body would come to rest over leaves the feet, the leg farther
  * from it swings there (lifting its foot on the way) while the other leg stays planted under its hip.
  */
 private void balance(){
  if(!active||fallen||!rig.upright())return;double strength=PhysicalistConfig.RAGDOLL_BALANCE.get();if(strength<=0)return;
  var torso=parts.get(1);Vec3 up=torso.vector(new Vec3(0,1,0));
  if(up.y<Math.cos(1.0)){fallen=true;stepLeg=-1;return;}
  double total=0;Vec3 com=Vec3.ZERO,vel=Vec3.ZERO;for(var p:parts){double m=1/p.inverseMass;total+=m;com=com.add(p.position.scale(m));vel=vel.add(p.velocity.scale(m));}com=com.scale(1/total);vel=vel.scale(1/total);
  Vec3[] foot=new Vec3[2];for(int k=0;k<2;k++){var leg=parts.get(4+k);foot[k]=leg.position.add(leg.vector(new Vec3(0,-leg.half.y,0)));}
  // In the air (thrown, held by the wand) there is nothing to balance on; it catches itself when it lands.
  boolean grounded=false;for(var f:foot)grounded|=level().getBlockCollisions(this,new AABB(f,f).inflate(.12,.1,.12)).iterator().hasNext();
  if(!grounded){stepLeg=-1;stepTicks=0;return;}
  Vec3 support=foot[0].add(foot[1]).scale(.5);double scale=rig.center[1].y/1.125,g=Math.max(1e-4,PhysicalistConfig.defaults().gravity());
  double height=Math.max(.3*scale,com.y-support.y);
  Vec3 capture=new Vec3(com.x+vel.x*Math.sqrt(height/g),support.y,com.z+vel.z*Math.sqrt(height/g));
  Vec3 offset=new Vec3(capture.x-support.x,0,capture.z-support.z);
  if(stepTicks>0&&--stepTicks==0)stepLeg=-1;
  if(stepLeg<0&&offset.length()>.1*scale){
   stepLeg=foot[0].distanceToSqr(capture)>foot[1].distanceToSqr(capture)?0:1;stepTicks=STEP_TICKS;
   Vec3 reach=offset.scale(1.2);if(reach.length()>.7*scale)reach=reach.normalize().scale(.7*scale);
   stepTarget=support.add(reach);
  }
  // Caught: a last step brings the far foot back under its hip, as a person gathers their feet after a stumble.
  // Left in a split stance, the next small wobble set off another step and the ragdoll wandered off.
  if(stepLeg<0&&offset.length()<.05*scale&&new Vec3(vel.x,0,vel.z).length()<.02){
   Vec3 under=new Vec3(torso.position.x,support.y,torso.position.z);int far=-1;double worst=.14*scale;
   for(int k=0;k<2;k++){Vec3 hip=under.add(flat(torso.vector(new Vec3(1,0,0))).scale(rig.torsoAnchor[4+k].x));double d=new Vec3(foot[k].x-hip.x,0,foot[k].z-hip.z).length();if(d>worst){worst=d;far=k;}}
   if(far>=0){stepLeg=far;stepTicks=STEP_TICKS;stepTarget=under;}
  }
  Vec3 flatForward=flat(torso.vector(new Vec3(1,0,0)));
  for(int k=0;k<2;k++){
   var leg=parts.get(4+k);Vec3 hip=torso.position.add(torso.vector(rig.torsoAnchor[4+k]));Vec3 target;
   if(k==stepLeg){double side=rig.torsoAnchor[4+k].x;target=stepTarget.add(flatForward.scale(side)).add(0,.14*scale*Math.sin(Math.PI*stepTicks/STEP_TICKS),0);}
   else target=foot[k];
   Vec3 d=hip.subtract(target);if(d.lengthSqr()<1e-6)continue;
   // The swinging leg turns about the hip; the planted one about its foot, which stays where it stands (turned
   // about the hip it slid along under the body, and a ragdoll shoved back skated forward).
   turnUp(leg,d.normalize(),(k==stepLeg?.6:.3)*strength);if(k==stepLeg)pivot(leg,torso,hip);else leg.velocity=leg.angular.cross(leg.position.subtract(foot[k]));
  }
  // Braking: planted feet take the body's way out (as friction under pushing legs would). Leaning back against the
  // motion keeps the centre of mass behind the feet; leaning into it made the ragdoll run off and never stop.
  {Vec3 brake=new Vec3(vel.x,0,vel.z).scale(.12*Math.min(1,strength));
   // Ankles: with both feet down, the body is drawn back over the middle of its feet. Without it a ragdoll that
   // had caught itself kept leaning a little, crept off at walking pace and never lowered its arms.
   if(stepLeg<0){Vec3 ankle=new Vec3(support.x-com.x,0,support.z-com.z).scale(.06*Math.min(1,strength));if(ankle.length()>.012)ankle=ankle.normalize().scale(.012);brake=brake.subtract(ankle);}
   for(var p:parts)p.velocity=p.velocity.subtract(brake);}
  Vec3 lean=offset.length()>1e-6?offset.normalize().scale(-Math.min(.2,offset.length()*.5)):Vec3.ZERO;
  turnUp(torso,new Vec3(0,1,0).add(lean).normalize(),.45*strength);
  // Keep facing the same way: a free twist let every shove turn the ragdoll and it walked off sideways.
  {Vec3 f=flat(torso.vector(new Vec3(0,0,1)));double yaw=Math.atan2(f.x,f.z);if(Double.isNaN(heading))heading=yaw;
   double error=Math.atan2(Math.sin(heading-yaw),Math.cos(heading-yaw));Vec3 u=torso.vector(new Vec3(0,1,0));double spin=torso.angular.dot(u);
   torso.angular=torso.angular.add(u.scale((error*.25*strength-spin)*.6));}
  // Standing height: the hips sank between the legs while stepping, as if it crawled on its knees.
  {double sag=support.y+rig.center[1].y-torso.position.y;if(sag>.03*scale){double lift=Math.min(.05,sag*.3);for(int i=0;i<4;i++)parts.get(i).velocity=parts.get(i).velocity.add(0,lift,0);}}
  turnUp(parts.get(0),new Vec3(0,1,0),.25*strength);pivot(parts.get(0),torso,torso.position.add(torso.vector(rig.torsoAnchor[0])));
  // Arms balance: the harder the body is thrown off its feet, the further they fly out to the sides and against the
  // fall (forward when falling back), the way people windmill to catch themselves; calm, they hang.
  double alarm=Math.clamp(offset.length()/(.3*scale),0,1);if(stepLeg>=0)alarm=Math.max(alarm,.35);Vec3 against=offset.length()>1e-6?offset.normalize().scale(-1):Vec3.ZERO;
  for(int k=0;k<2;k++){double side=rig.torsoAnchor[2+k].x>0?1:-1;var arm=parts.get(2+k);
   Vec3 reach=new Vec3(0,-1,0).scale(1-alarm).add(flatForward.scale(side*.9).add(against.scale(.8)).add(0,.35,0).scale(alarm));
   if(reach.lengthSqr()<1e-6)reach=new Vec3(0,-1,0);
   turnUp(arm,reach.normalize().scale(-1),(.15+.35*alarm)*strength);pivot(arm,torso,torso.position.add(torso.vector(rig.torsoAnchor[2+k])));}
 }
 /**
  * A limb turned by the controller swings about its joint, not its own middle: its centre moves with the joint.
  * Turning it about the middle tore the hip apart (legs drawn away from the body) until the joint pulled it back.
  */
 private static void pivot(RigidBodyState limb,RigidBodyState torso,Vec3 joint){
  limb.velocity=torso.pointVelocity(joint).add(limb.angular.cross(limb.position.subtract(joint)));
 }
 private static Vec3 flat(Vec3 v){Vec3 f=new Vec3(v.x,0,v.z);return f.lengthSqr()<1e-8?new Vec3(1,0,0):f.normalize();}
 /** Steers a limb's angular velocity so its local up axis turns toward a direction, keeping a little of its twist. */
 private static void turnUp(RigidBodyState part,Vec3 direction,double gain){
  Vec3 u=part.vector(new Vec3(0,1,0)),error=u.cross(direction);if(u.dot(direction)<0)error=error.lengthSqr()<1e-8?new Vec3(1,0,0):error.normalize();
  Vec3 twist=u.scale(part.angular.dot(u)*.6);Vec3 goal=error.scale(gain).add(twist);
  if(goal.subtract(part.angular).lengthSqr()<1e-6)return;
  Vec3 next=part.angular.lerp(goal,.7);if(next.length()>.6)next=next.normalize().scale(.6);part.angular=next;
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
  if(++age>=PhysicalistConfig.RAGDOLL_LIFETIME.get()*20){discard();return;}
  if(getDeltaMovement().lengthSqr()>1e-8){for(var p:parts)p.velocity=p.velocity.add(getDeltaMovement());setDeltaMovement(Vec3.ZERO);sleeping=false;quiet=0;}
  balance();
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
 @Override protected void addAdditionalSaveData(CompoundTag tag){ensure();tag.put("Ragdoll",state());tag.put("Mob",mob);tag.putBoolean("Active",active);tag.putBoolean("Fallen",fallen);}
 @Override protected void readAdditionalSaveData(CompoundTag tag){setMob(tag.getCompound("Mob"));readState(tag.getCompound("Ragdoll"),false);active=tag.getBoolean("Active");fallen=tag.getBoolean("Fallen");publish();}
 @Override public boolean isPickable(){return true;}
 /** A hit shoves the ragdoll away from the attacker, hardest at the limb that was hit. */
 @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source,float amount){
  if(level().isClientSide||isInvulnerableTo(source))return false;ensure();Entity by=source.getEntity();
  Vec3 direction=by instanceof LivingEntity living?living.getLookAngle():by!=null?position().subtract(by.position()):new Vec3(0,1,0);
  direction=new Vec3(direction.x,Math.max(direction.y,0)*.3+.05,direction.z);if(!RigidBodyState.finite(direction)||direction.lengthSqr()<1e-8)direction=new Vec3(0,1,0);
  Vec3 push=direction.normalize().scale(Math.min(.5,.14+.02*amount));int hit=1;
  if(by instanceof Player player){Vec3 eye=player.getEyePosition(),end=eye.add(player.getLookAngle().scale(8));double nearest=Double.POSITIVE_INFINITY;for(int i=0;i<parts.size();i++){double f=PhysicalistTargeting.rayFraction(parts.get(i).box(),eye,end);if(f<nearest){nearest=f;hit=i;}}}
  for(int i=0;i<parts.size();i++)parts.get(i).velocity=parts.get(i).velocity.add(push.scale(i==hit?1.6:.8));sleeping=false;quiet=0;return true;
 }
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
