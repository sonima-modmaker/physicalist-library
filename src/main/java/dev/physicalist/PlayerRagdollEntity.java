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
  * Upright rigs also have anatomical ranges ({@link #range}), which replace the symmetric cone.
  */
 public record Rig(Vec3[] half,Vec3[] center,double[] mass,Vec3[] torsoAnchor,Vec3[] limbAnchor,double[] twist,double[] swing,boolean upright){
  private static Vec3 v(double x,double y,double z){return new Vec3(x,y,z);}
  /**
   * Anatomical joint range of a part relative to the torso, in radians, or null for the symmetric cone: {twist min,
   * twist max (about the limb's own y), flex min, flex max (about the torso's x: negative swings an arm or leg
   * forward, positive tips the head forward), side min, side max (about the torso's z: positive swings toward +x)}.
   * Shoulders lift the arm forward and up over the head but back only about 50 degrees, and out sideways but barely
   * across the chest; hips raise the leg forward to about 115 degrees, back about 25, out 45 and in 17; the neck nods
   * and turns but cannot spin round.
   */
  public double[] range(int part){
   if(!upright||part==1)return null;
   if(part==0)return new double[]{-1.2,1.2,-.8,1.0,-.6,.6};
   double side=Math.signum(torsoAnchor[part].x);boolean arm=part<4;
   double out=arm?2.9:.8,in=arm?.3:.3;double lo=side>0?-in:-out,hi=side>0?out:in;
   return arm?new double[]{-1.0,1.0,-3.0,.9,lo,hi}:new double[]{-.5,.5,-2.0,.45,lo,hi};
  }
  /** A broken bone: the limb flops about loosely, held on only by the flesh. */
  public static final double[] BROKEN={-2.6,2.6,-3.0,3.0,-3.0,3.0};
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
  static Rig quadruped(double width,double height){return quadruped(width,height,false);}
  /** A blob (slimes, magma cubes) has no legs to stand on: stubs under a body as big as the mob. */
  static Rig quadruped(double width,double height,boolean blob){
   double legs=blob?Math.max(.03,height*.04):Math.clamp(height*.45,.12,2),body=blob?height-legs:Math.max(.15,(height-legs)*.85),length=width*1.1,side=width*.36,leg=Math.clamp(width*.12,.04,.35),head=Math.clamp(width*.28,.08,.5);
   double mass=Math.clamp(width*width*height/(.6*.6*1.8),.1,25);Vec3 torso=v(0,legs+body/2,0);
   Vec3[] half=new Vec3[6],center=new Vec3[6],torsoAnchor=new Vec3[6],limbAnchor=new Vec3[6];
   half[1]=v(side,body/2,length/2);center[1]=torso;torsoAnchor[1]=limbAnchor[1]=Vec3.ZERO;
   half[0]=v(head,head,head);torsoAnchor[0]=v(0,body*.3,length/2);limbAnchor[0]=v(0,-head*.3,-head);center[0]=torso.add(torsoAnchor[0]).subtract(limbAnchor[0]).add(0,0,.02);
   for(int i=2;i<6;i++){double x=(i%2==0?-1:1)*(side-leg),z=(i<4?1:-1)*(length/2-leg*1.5);// A gap below the torso, as at the player's hips: a leg flush with it hit the torso as soon as it tilted and held the animal up.
    double gap=Math.min(.05,legs*.15);half[i]=v(leg,(legs-gap)/2,leg);center[i]=v(x,(legs-gap)/2,z);torsoAnchor[i]=v(x,-body/2,z);limbAnchor[i]=v(0,(legs-gap)/2+gap,0);}
   return new Rig(half,center,new double[]{mass*.12,mass*.6,mass*.07,mass*.07,mass*.07,mass*.07},torsoAnchor,limbAnchor,new double[]{.6,0,.3,.3,.3,.3},new double[]{.8,0,1.1,1.1,1.1,1.1},false);
  }
  static Rig of(double width,double height){return of(width,height,false);}
  static Rig of(double width,double height,boolean blob){return blob?quadruped(width,height,true):height>=width*1.6?humanoid(width,height):quadruped(width,height);}
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
 /** Limp ragdoll copy of any entity, standing where it stands and facing where it faces; it starts tipping over. */
 public void initialize(Entity source){
  if(source instanceof Player player){owner=player.getGameProfile();setPos(source.position().add(0,rig.center[1].y,0));ensure();}
  else{
   var tag=new CompoundTag();tag.putString("Type",EntityType.getKey(source.getType()).toString());tag.putDouble("W",source.getBbWidth());tag.putDouble("H",source.getBbHeight());if(source instanceof net.minecraft.world.entity.monster.Slime)tag.putBoolean("Blob",true);
   var data=source.saveWithoutId(new CompoundTag());for(String key:List.of("Pos","Motion","Rotation","UUID","Passengers","Brain","Inventory","Items","ArmorItems","HandItems","Attributes","ActiveEffects","Leash","Offers","Gossips","HurtTime","DeathTime","Health","CustomName"))data.remove(key);
   if(data.sizeInBytes()<8192)tag.put("Data",data);setMob(tag);
   setPos(source.position().add(0,rig.center[1].y,0));ensure();
  }
  place(source instanceof LivingEntity living?living.yBodyRot:source.getYRot(),true,source.getDeltaMovement(),source.getY());
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

 /**
  * Thrown items (and stacks of blocks) that fly into a limb knock it: the item's momentum goes into that limb, a
  * share into the whole body, and the item bounces off. A dirt block thrown with Q makes it stagger and step; a
  * whole stack can knock it over.
  */
 private void thrownItems(){
  double total=0;for(double m:rig.mass)total+=m;
  for(var item:level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,getBoundingBox().inflate(1))){
   Vec3 v=item.getDeltaMovement();if(v.length()<.15)continue;
   Vec3 at=item.position().add(0,item.getBbHeight()/2,0);int hit=-1;double nearest=Double.POSITIVE_INFINITY;
   for(int i=0;i<6;i++){var b=parts.get(i).box();var grown=new CompoundCollision.Box(b.center(),b.axes(),new double[]{b.half()[0]+.15,b.half()[1]+.15,b.half()[2]+.15});
    double f=PhysicalistTargeting.rayFraction(grown,at.subtract(v),at.add(v));if(f<nearest){nearest=f;hit=i;}}
   if(hit<0)continue;
   var stack=item.getItem();double mass=(stack.getItem() instanceof net.minecraft.world.item.BlockItem?1.5:.4)*Math.sqrt(Math.min(stack.getCount(),64));
   Vec3 kick=v.scale(1.2*mass);var limb=parts.get(hit);
   limb.velocity=limited(limb.velocity.add(kick.scale(.6/rig.mass[hit])),3);
   for(var p:parts)p.velocity=limited(p.velocity.add(kick.scale(.4/total)),3);
   item.setDeltaMovement(v.scale(-.25).add(0,.05,0));sleeping=false;quiet=0;
  }
 }
 private void setMob(CompoundTag tag){mob=tag;rig=tag.contains("Type")?Rig.of(Math.clamp(tag.getDouble("W"),.1,6),Math.clamp(tag.getDouble("H"),.1,6),tag.getBoolean("Blob")):Rig.PLAYER;if(ready){ready=false;parts.clear();joints.clear();}if(!level().isClientSide)entityData.set(MOB,tag);}
 private void ensure(){if(ready)return;ready=true;Vec3 torso=rig.center[1];for(int i=0;i<6;i++)parts.add(new RigidBodyState(position().add(rig.center[i].subtract(torso)),rig.half[i],rig.mass[i]));
  for(int i=0;i<6;i++)if(i!=1){var joint=ElasticJoint.socket(parts.get(1),parts.get(i),rig.torsoAnchor[i],rig.limbAnchor[i],COMPLIANCE[i],DAMPING[i]);double[] range=jointRange(i);joints.add(range!=null?joint.range(range):joint.limit(new Vec3(0,1,0),new Vec3(0,1,0),rig.swing[i]));}
  snapshot();
 }

 private int broken;private final int[] strain=new int[6];private int gripTicks,gripPart=-1;
 /** Anatomical range of a part's joint now (loose once its bone is broken), or null for the plain cone. */
 public double[] jointRange(int part){if(part==1)return null;return (broken>>part&1)!=0?Rig.BROKEN:rig.range(part);}
 public boolean boneBroken(int part){return (broken>>part&1)!=0;}
 /** Bit i set: part i's bone is broken. */
 public int brokenMask(){return broken;}
 /** Breaks the bone of a limb (or the neck): it snaps with a crack and flops loosely from then on. */
 public void breakBone(int part){
  if(part==1||part<0||part>5||boneBroken(part)||rig.range(part)==null)return;ensure();broken|=1<<part;strain[part]=0;
  for(var joint:joints)if(joint.b==parts.get(part)&&joint.range()!=null)joint.range(Rig.BROKEN);
  var limb=parts.get(part);var at=limb.position;
  level().playSound(null,at.x,at.y,at.z,PhysicalistContent.BONE_BREAK.get(),net.minecraft.sounds.SoundSource.NEUTRAL,1.2F,.9F+level().getRandom().nextFloat()*.2F);
  if(level() instanceof ServerLevel server)server.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BONE)),at.x,at.y,at.z,8,.08,.08,.08,.06);
  sleeping=false;quiet=0;
 }
 /**
  * Bones give way when a limb held by the wand is wrenched past its range (or torn out of its socket) for a few
  * ticks in a row: grab someone by the arm, twist it or yank the body about by it, and the arm snaps.
  */
 private void bones(){
  if(gripTicks>0)gripTicks--;else gripPart=-1;
  var torso=parts.get(1);double scale=rig.center[1].y/1.125;
  for(int i=0;i<6;i++){double[] range=jointRange(i);if(range==null||boneBroken(i)){strain[i]=0;continue;}
   double gap=torso.point(rig.torsoAnchor[i]).distanceTo(parts.get(i).point(rig.limbAnchor[i]));
   // The joint holds firm at its limit, so what counts is how far the wand forces the limb past it (or how hard it
   // tugs on a limb that will not follow, or tears it out of its socket), not how far the limb bends: just lifting
   // someone by the arm, the hanging body swings the shoulder up to about 1.1 rad past its range, and that must not
   // snap it (twisting it with the wand, or a sharp turn while holding it, goes well beyond).
   boolean held=gripTicks>0&&gripPart==i;
   boolean wrenched=held&&(gripWrench>1.5||gripTug>1.5*scale||gap>.45*scale);strain[i]=wrenched?strain[i]+1:0;
   if(strain[i]>=3)breakBone(i);}
 }
 private double gripWrench,gripTug;
 /** Strain on a held limb, for tests: {wand twist past range, tug distance, socket gap}. */
 double[] gripStrain(int i){var torso=parts.get(1);return new double[]{gripWrench,gripTug,torso.point(rig.torsoAnchor[i]).distanceTo(parts.get(i).point(rig.limbAnchor[i]))};}
 private static double overshoot(double[] a,double[] range){return Math.max(Math.max(beyond(a[1],range[0],range[1]),beyond(a[0],range[2],range[3])),beyond(a[2],range[4],range[5]));}
 private static double beyond(double v,double lo,double hi){return v<lo?lo-v:v>hi?v-hi:0;}
 /** Limb the wand is holding (-1 for none). */
 public int grippedPart(){return gripTicks>0?gripPart:-1;}
 /**
  * The wand holding one limb by a point on it: that point is pulled to goal and the limb turned to orientation, the
  * rest of the body hangs off it through its joints.
  */
 public void grabLimb(int part,Vec3 local,Vec3 goal,Quaternionf orientation){
  ensure();if(part<0||part>5)return;var p=parts.get(part);
  Quaternionf err=new Quaternionf(orientation).mul(new Quaternionf(p.orientation).conjugate());if(err.w<0)err.set(-err.x,-err.y,-err.z,-err.w);
  double angle=2*Math.acos(Math.min(1,err.w)),sin=Math.sqrt(Math.max(1e-12,1-err.w*err.w));
  p.angular=angle<1e-4?Vec3.ZERO:limited(new Vec3(err.x/sin,err.y/sin,err.z/sin).scale(angle*.5),1);
  Vec3 arm=rotate(orientation,local);
  double[] range=jointRange(part);gripWrench=range==null?0:overshoot(ElasticJoint.angles(parts.get(1).orientation,orientation),range);
  gripTug=goal.distanceTo(p.position.add(rotate(p.orientation,local)));
  p.velocity=GrabController.velocity(goal.subtract(arm).subtract(p.position),p.velocity,PhysicalistConfig.defaults().gravity());
  gripPart=part;gripTicks=3;sleeping=false;quiet=0;
 }
 private static Vec3 rotate(Quaternionf q,Vec3 v){var r=q.transform(new org.joml.Vector3f((float)v.x,(float)v.y,(float)v.z));return new Vec3(r.x,r.y,r.z);}
 /** Parts the wand froze in place (bit i: part i). */
 public int frozenParts(){return getPersistentData().getInt(PhysicalistFreeze.PARTS);}
 private void snapshot(){for(int i=0;i<6;i++){previous[i]=parts.get(i).position;previousQ[i]=new Quaternionf(parts.get(i).orientation);}}
 public Vec3 renderPosition(int i,float partial){ensure();return previous[i].lerp(parts.get(i).position,partial);}
 public Quaternionf renderRotation(int i,float partial){ensure();return new Quaternionf(previousQ[i]).slerp(parts.get(i).orientation,partial);}
 @Override public void tick(){super.tick();ensure();snapshot();
  if(level().isClientSide){if(lerp>0){double alpha=1.0/lerp--;for(int i=0;i<6;i++){parts.get(i).position=parts.get(i).position.lerp(target[i],alpha);parts.get(i).orientation.slerp(targetQ[i],(float)alpha);}}refresh();return;}
  if(++age>=PhysicalistConfig.RAGDOLL_LIFETIME.get()*20){discard();return;}
  if(getDeltaMovement().lengthSqr()>1e-8){for(var p:parts)p.velocity=p.velocity.add(getDeltaMovement());setDeltaMovement(Vec3.ZERO);sleeping=false;quiet=0;}
  thrownItems();bones();
  {int frozen=frozenParts();if(frozen!=0)for(int i=0;i<6;i++)if((frozen>>i&1)!=0){parts.get(i).velocity=Vec3.ZERO;parts.get(i).angular=Vec3.ZERO;}}
  if(dev.physicalist.nativecore.NativeWorlds.enqueue(this))return;
  if(sleeping&&PhysicalistExternalCollisions.available()&&tickCount%5==0&&!PhysicalistExternalCollisions.gather((ServerLevel)level(),this,getBoundingBox().inflate(.1)).isEmpty()){sleeping=false;quiet=0;}
  if(sleeping){if(tickCount%10!=0||supported()){refresh();return;}sleeping=false;quiet=0;}
  long timer=PhysicsMetrics.begin();try{simulate();}finally{PhysicsMetrics.end(timer);}refresh();if(tickCount%2==0)publish();
 }
 /** Resolve invalid overlapping spawn poses without turning depenetration into velocity. */
 public void relaxInitialSelfContacts(){ensure();boolean overlap=false;for(int a=0;a<6;a++)for(int b=a+1;b<6;b++){var c=RagdollSelfCollision.contact(parts,a,b,0);if(c!=null&&c.depth()>.01)overlap=true;}if(!overlap)return;for(var joint:joints)joint.beginStep();for(int i=0;i<96;i++){for(var joint:joints)joint.solve(1);RagdollSelfCollision.project(parts);}refresh();}
 public void nativeStepFinished(boolean asleep){sleeping=asleep;refresh();if(tickCount%2==0||asleep)publish();}
 private boolean supported(){var l=(ServerLevel)level();for(var p:parts)if(l.getBlockCollisions(this,CompoundCollision.bounds(p.box()).move(0,-.025,0)).iterator().hasNext())return true;return false;}
 private final Vec3[] pinned=new Vec3[6];private final Quaternionf[] pinnedQ=new Quaternionf[6];
 private void simulate(){
  // Frozen parts (Java fallback): held where they are while the rest moves around them.
  {int frozen=frozenParts();for(int i=0;i<6;i++)if((frozen>>i&1)!=0){if(pinned[i]==null){pinned[i]=parts.get(i).position;pinnedQ[i]=new Quaternionf(parts.get(i).orientation);}}else pinned[i]=null;}
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
    for(int i=0;i<6;i++)if(pinned[i]!=null){var p=parts.get(i);p.position=pinned[i];p.orientation.set(pinnedQ[i]);p.velocity=Vec3.ZERO;p.angular=Vec3.ZERO;}
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
 @Override protected void addAdditionalSaveData(CompoundTag tag){ensure();tag.put("Ragdoll",state());tag.put("Mob",mob);tag.putInt("Broken",broken);}
 @Override protected void readAdditionalSaveData(CompoundTag tag){setMob(tag.getCompound("Mob"));readState(tag.getCompound("Ragdoll"),false);broken=tag.getInt("Broken")&0b111101;for(var joint:joints)for(int i=0;i<6;i++)if(joint.b==parts.get(i)&&joint.range()!=null)joint.range(jointRange(i));publish();}
 @Override public boolean isPickable(){return true;}
 /** A hit shoves the ragdoll away from the attacker, hardest at the limb that was hit. */
 @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source,float amount){
  if(level().isClientSide||isInvulnerableTo(source))return false;ensure();Entity by=source.getEntity(),direct=source.getDirectEntity();
  // An arrow or a snowball pushes along its flight, not along where its shooter happens to look now.
  boolean flying=direct!=null&&direct!=by&&direct.getDeltaMovement().lengthSqr()>1e-4;
  Vec3 direction=flying?direct.getDeltaMovement():by instanceof LivingEntity living?living.getLookAngle():by!=null?position().subtract(by.position()):new Vec3(0,1,0);
  direction=new Vec3(direction.x,Math.max(direction.y,0)*.3+.05,direction.z);if(!RigidBodyState.finite(direction)||direction.lengthSqr()<1e-8)direction=new Vec3(0,1,0);
  Vec3 push=direction.normalize().scale(Math.min(.5,.14+.02*amount));int hit=1;
  if(flying){Vec3 from=direct.position().subtract(direct.getDeltaMovement().scale(2)),to=direct.position().add(direct.getDeltaMovement().scale(2));double nearest=Double.POSITIVE_INFINITY;for(int i=0;i<parts.size();i++){double f=PhysicalistTargeting.rayFraction(parts.get(i).box(),from,to);if(f<nearest){nearest=f;hit=i;}}}
  else if(by instanceof Player player){Vec3 eye=player.getEyePosition(),end=eye.add(player.getLookAngle().scale(8));double nearest=Double.POSITIVE_INFINITY;for(int i=0;i<parts.size();i++){double f=PhysicalistTargeting.rayFraction(parts.get(i).box(),eye,end);if(f<nearest){nearest=f;hit=i;}}}
  for(int i=0;i<parts.size();i++)parts.get(i).velocity=parts.get(i).velocity.add(push.scale(i==hit?1.6:.8));
  sleeping=false;quiet=0;return true;
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
