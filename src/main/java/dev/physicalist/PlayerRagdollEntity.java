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
 /** Leg (0 or 1) swinging in a step right now, or -1 with both feet down. */
 public int steppingLeg(){return stepLeg;}
 /** World point under the sole of leg 0 or 1. */
 public Vec3 foot(int k){ensure();var leg=parts.get(4+k);return leg.position.add(leg.vector(new Vec3(0,-leg.half.y,0)));}
 /**
  * Active ragdolls (upright ones: players, zombies, villagers...) lean against a push and step under their centre of
  * mass to stay on their feet; once tipped past recovery they go limp. Turning it off makes the ragdoll limp at once.
  */
 public void setActive(boolean value){
  // Going limp the body sags and folds a little, the way a person's knees give: left squarely in its stance with
  // nothing moving, it stood on like a statue.
  if(active&&!value&&!parts.isEmpty()){var r=level().getRandom();Vec3 sag=new Vec3(r.nextGaussian(),0,r.nextGaussian()).normalize().scale(.03);
   for(int i=0;i<parts.size();i++){var p=parts.get(i);p.velocity=p.velocity.add(i<4?sag:sag.scale(-.5));}}
  compose=0;active=value;fallen=false;stepLeg=-1;stepTicks=0;heading=Double.NaN;hold[0]=hold[1]=null;struggle=0;sleeping=false;quiet=0;}
 private static final int STEP_TICKS=6;private static final double ANKLE=.08,ANKLE_CAP=.02,ARMS=.04;
 /** Ground spot each planted foot stands on, or null while it swings. */
 private final Vec3[] hold=new Vec3[2];
 /**
  * Balance as a capture-point controller on the limbs' velocities (applied before each physics step): the torso and
  * head are turned upright, and when the point the body would come to rest over leaves the feet, the leg farther
  * from it swings there (lifting its foot on the way) while the other leg stays planted under its hip.
  */
 private void balance(){
  if(!active||!rig.upright())return;double strength=PhysicalistConfig.RAGDOLL_BALANCE.get();if(strength<=0)return;
  var torso=parts.get(1);Vec3 up=torso.vector(new Vec3(0,1,0));
  if(fallen){standUp(strength);return;}
  if(up.y<Math.cos(1.0)){fallTicks=0;fallen=true;stepLeg=-1;downTicks=0;getUp=0;alarm=0;hold[0]=hold[1]=null;return;}
  double total=0;Vec3 com=Vec3.ZERO,vel=Vec3.ZERO;for(var p:parts){double m=1/p.inverseMass;total+=m;com=com.add(p.position.scale(m));vel=vel.add(p.velocity.scale(m));}com=com.scale(1/total);vel=vel.scale(1/total);
  Vec3[] foot=new Vec3[2];for(int k=0;k<2;k++){var leg=parts.get(4+k);foot[k]=leg.position.add(leg.vector(new Vec3(0,-leg.half.y,0)));}
  // In the air (thrown, held by the wand) there is nothing to balance on; it catches itself when it lands.
  boolean grounded=false;for(var f:foot)grounded|=level().getBlockCollisions(this,new AABB(f,f).inflate(.12,.1,.12)).iterator().hasNext();
  double scale=rig.center[1].y/1.125;
  if(!grounded){stepLeg=-1;stepTicks=0;hold[0]=hold[1]=null;
   // Sitting or kneeling on the ground with its feet up is not flying: any part resting on the ground counts as
   // stuck (below), not as "in the air". Thrown or held by the wand, nothing touches the ground.
   boolean resting=false;for(var p:parts)resting|=level().getBlockCollisions(this,CompoundCollision.bounds(p.box()).move(0,-.12,0)).iterator().hasNext();
   if(resting&&stuck(true))fallDown();
   return;}
  Vec3 support=foot[0].add(foot[1]).scale(.5);double g=Math.max(1e-4,PhysicalistConfig.defaults().gravity());
  if(composeCool>0)composeCool--;
  if(compose>0){
   // Pushed while straightening up: back to balancing at once.
   if(new Vec3(vel.x,0,vel.z).length()>.08||up.y<.8)compose=0;
   else{compose(g,strength);return;}}
  // Leaning far back or sunk low for over a second and a half (legs out in front, half sitting, wedged against a
  // wall or a ledge) it gives up and falls, then gets up properly. It used to slide along in that pose for ever,
  // straining to straighten up.
  // Height is measured from the lowest point of the body, not the feet: sitting with its legs raised in front, the
  // feet were up too and it did not look sunk at all.
  double floorY=Double.MAX_VALUE;for(var p:parts)floorY=Math.min(floorY,CompoundCollision.bounds(p.box()).minY);
  if(stuck(up.y<.85||floorY+rig.center[1].y-torso.position.y>.25*scale)){fallDown();return;}
  double height=Math.max(.3*scale,com.y-support.y),reachTime=Math.sqrt(height/g);
  Vec3 capture=new Vec3(com.x+vel.x*reachTime,support.y,com.z+vel.z*reachTime);
  Vec3 offset=new Vec3(capture.x-support.x,0,capture.z-support.z);
  // Walking: steps follow the real motion (the capture point runs ahead of the feet, so the legs keep stepping
  // under it), while the brake below drives the body toward the walking velocity instead of to a stop. Leaning and
  // the arms answer only to how far the motion is off that velocity, or every walk would look like a stumble.
  Vec3 desired=walkVelocity(com,torso,scale);boolean walking=desired.lengthSqr()>1e-8;Vec3 rel=vel.subtract(desired);
  Vec3 upset=walking?new Vec3(com.x+rel.x*reachTime-support.x,0,com.z+rel.z*reachTime-support.z):offset;
  if(shoveTicks>0)shoveTicks--;if(stepTicks>0&&--stepTicks==0){hold[stepLeg]=null;stepLeg=-1;calm=0;if(!walking)restless++;}restless*=.985;
  if(stepLeg<0)calm++;if(settling>0)settling--;
  Vec3 flatForward=flat(torso.vector(new Vec3(1,0,0))),front=flat(torso.vector(new Vec3(0,0,1)));
  // A planted foot holds the spot it landed on: the body's push reaches the ground through the leg and the foot
  // stays, as a shoe on stone does. Held only by contact friction the barely loaded feet skated a block or more.
  // The grip has a limit: a foot dragged further than a few centimetres from its spot skids along (the spot follows
  // it), so a hard shove slides it a little instead of pinning the feet and flipping the body over them.
  for(int k=0;k<2;k++)if(k!=stepLeg){if(hold[k]==null)hold[k]=foot[k];else{Vec3 pull=new Vec3(hold[k].x-foot[k].x,0,hold[k].z-foot[k].z);if(pull.length()>.1)hold[k]=foot[k].add(pull.normalize().scale(.1));}}
  // Step at once when thrown hard; after a small wobble wait a moment with both feet down (stepping again at once
  // made it shuffle off).
  // Small offsets are left to the ankles: stepping for each of them, every step's own sway set off the next and it
  // walked off in tiny steps.
  // Setting off from a stand nothing tips it far enough to step, so a walker that has stood still a moment just
  // takes the first step (it leaned on its planted feet and never moved).
  if(stepLeg<0&&(shoveTicks>0&&calm>0||offset.length()>.14*scale&&(calm>(walking?1:4)||offset.length()>.35*scale)||walking&&arriving==0&&calm>8)){
   double lateral=offset.dot(flatForward),ahead=offset.dot(front);
   // Thrown sideways it steps out with the leg on that side; the far leg had to cross over the other one.
   if(Math.abs(lateral)>Math.abs(ahead)*.8)stepLeg=(lateral>0)==(rig.torsoAnchor[5].x>0)?1:0;
   else stepLeg=foot[0].distanceToSqr(capture)>foot[1].distanceToSqr(capture)?0:1;
   stepTicks=STEP_TICKS;hold[stepLeg]=null;gathers=0;
   Vec3 reach=offset.scale(1.2);
   // Stepping off a blow: the foot goes back along the push a little further than the body will drift.
   if(shoveTicks>0&&!walking){Vec3 d=shoveDir.normalize();double far=Math.max(offset.dot(d)*1.2,(.22+shoveDir.length()*2)*scale);reach=d.scale(far).add(offset.subtract(d.scale(offset.dot(d))));shoveTicks=0;}
   // Walking, the foot lands a stride ahead of the capture point: put just under it, each step only caught up with
   // the body and it shuffled along in short steps (and stalled).
   // Strides are kept short: a long one left the back foot far behind, the legs split over a metre and it sank.
   if(walking){reach=offset.add(desired.scale(STEP_TICKS));
    // Never a step on the spot: a walker that crept up short of its goal stood shuffling there for ever.
    Vec3 dir=desired.normalize();double along=reach.dot(dir);if(arriving==0&&along<.25*scale)reach=reach.add(dir.scale(.25*scale-along));}
   double longest=(walking?.45:.75)*scale;if(reach.length()>longest)reach=reach.normalize().scale(longest);
   stepTarget=support.add(reach);
   // Never across the other foot.
   double side=Math.signum(rig.torsoAnchor[4+stepLeg].x),own=stepTarget.add(flatForward.scale(rig.torsoAnchor[4+stepLeg].x)).subtract(foot[1-stepLeg]).dot(flatForward)*side;
   if(own<.18*scale)stepTarget=stepTarget.add(flatForward.scale(side*(.18*scale-own)));
  }
  // Caught: a last step brings the far foot back under its hip, as a person gathers their feet after a stumble.
  // Left in a split stance, the next small wobble set off another step and the ragdoll wandered off.
  // At most two such steps after each real one: gathering one foot left the other a little off, the next gather
  // fixed that one, and it shuffled in place for ever, its feet creeping across the floor.
  if(stepLeg<0&&!walking&&gathers<2&&calm>6&&offset.length()<.08*scale&&new Vec3(vel.x,0,vel.z).length()<.03){
   Vec3 under=new Vec3(torso.position.x,support.y,torso.position.z);int far=-1;double worst=.2*scale;
   for(int k=0;k<2;k++){Vec3 hip=under.add(flatForward.scale(rig.torsoAnchor[4+k].x));double d=new Vec3(foot[k].x-hip.x,0,foot[k].z-hip.z).length();if(d>worst){worst=d;far=k;}}
   if(far>=0){stepLeg=far;stepTicks=STEP_TICKS;stepTarget=under;hold[far]=null;gathers++;}
  }
  // Standing still but bent off its feet (weight over one foot, limbs left twisted after a walk or a stumble), it
  // straightens up into its normal stance over the middle of its feet. Left so, it went on taking small steps for
  // as long as it stood, and its arms and legs never came back to their rest pose.
  if(stepLeg<0&&!walking&&composeCool==0&&alarm<.25&&(calm>15&&new Vec3(vel.x,0,vel.z).length()<.02&&new Vec3(com.x-support.x,0,com.z-support.z).length()>.07*scale||restless>1.8&&calm>2&&new Vec3(vel.x,0,vel.z).length()<.05)){restless=0;
   // A foot that is off its place under the hip is first stepped there (lifted and set down, as a person draws
   // their feet together); the straightening itself never slides the feet over the floor.
   Vec3 under=new Vec3(com.x,support.y,com.z);int far=-1;double worst=.07*scale;
   for(int k=0;k<2;k++){Vec3 hip=under.add(flatForward.scale(rig.torsoAnchor[4+k].x));double d=new Vec3(foot[k].x-hip.x,0,foot[k].z-hip.z).length();if(d>worst){worst=d;far=k;}}
   if(far>=0&&composeSteps<2){composeSteps++;stepLeg=far;stepTicks=STEP_TICKS;stepTarget=under;hold[far]=null;return;}
   composeSteps=0;beginCompose(support);return;}
  // Turning: with the feet planted the body cannot twist far (the legs hold it), so it turns in small steps, setting
  // each foot down again under its hip in the new direction. Without them, a walker that drifted sideways stood
  // stepping on the spot facing away from where it was going.
  if(stepLeg<0&&calm>3&&(walking||bot)&&!Double.isNaN(heading)){
   double yaw=Math.atan2(front.x,front.z),error=Math.atan2(Math.sin(heading-yaw),Math.cos(heading-yaw));
   if(Math.abs(error)>.3){Vec3 under=new Vec3(torso.position.x,support.y,torso.position.z);Vec3 turned=flatForward.yRot((float)(error*.5));int far=-1;double worst=.04*scale;
    for(int k=0;k<2;k++){Vec3 spot=under.add(turned.scale(rig.torsoAnchor[4+k].x));double d=new Vec3(foot[k].x-spot.x,0,foot[k].z-spot.z).length();if(d>worst){worst=d;far=k;}}
    if(far>=0){stepLeg=far;stepTicks=STEP_TICKS;hold[far]=null;stepTarget=under.add(turned.scale(rig.torsoAnchor[4+far].x)).subtract(flatForward.scale(rig.torsoAnchor[4+far].x));}}
  }
  int planted=0;
  for(int k=0;k<2;k++){
   var leg=parts.get(4+k);Vec3 hip=torso.position.add(torso.vector(rig.torsoAnchor[4+k]));Vec3 target;
   if(k==stepLeg){double side=rig.torsoAnchor[4+k].x;target=stepTarget.add(flatForward.scale(side)).add(0,.14*scale*Math.sin(Math.PI*stepTicks/STEP_TICKS),0);}
   else target=foot[k];
   Vec3 d=hip.subtract(target);if(d.lengthSqr()<1e-6)continue;
   // Both feet down: each leg pushes the hips back over the middle of the feet. Turned to follow the hip instead,
   // the planted leg pushed the body on in the direction it was already going and it shuffled off for metres.
   // Only the centre of mass is moved: putting each hip over the middle of a wide stance made the legs squeeze the
   // body between them and the feet crept across the floor.
   // Walking the planted leg just carries the hip on over its foot (an inverted pendulum).
   if(stepLeg<0&&!walking){Vec3 want=hip.add(support.x-com.x,0,support.z-com.z);d=want.subtract(foot[k]);if(d.lengthSqr()<1e-6)continue;}
   // The swinging leg turns about the hip; the planted one about its foot, which is pulled back onto its spot.
   turnUp(leg,d.normalize(),(k==stepLeg?.6:.3)*strength);
   // The legs turn with the body (the swinging one fully, the planted one about its foot). Nothing turned them, so
   // the hips' twist limit held the torso to the legs' old facing and the ragdoll could not turn to walk anywhere.
   // Like muscles the limbs also hold their twist: nothing turned them about their own axes, and after a fall or a
   // walk the arms and legs stayed turned in or out for good. A planted leg is left alone: its foot grips, and
   // twisting it against the ground rocked the whole body.
   if(k==stepLeg&&(walking||bot||settling>0))twistToward(leg,front,.5*strength);
   if(k==stepLeg)pivot(leg,torso,hip);
   else{planted++;Vec3 slip=hold[k]==null?Vec3.ZERO:new Vec3(hold[k].x-foot[k].x,0,hold[k].z-foot[k].z);leg.velocity=leg.angular.cross(leg.position.subtract(foot[k])).add(slip);}
  }
  // Braking: each planted foot takes the body's way out, as friction under a pushing leg does. Leaning back against
  // the motion keeps the centre of mass behind the feet; leaning into it made the ragdoll run off and never stop.
  // Walking, the same push drives the body on at the walking speed (and harder: at the braking gain the planted feet
  // held it back and it stepped on the spot).
  {Vec3 brake=new Vec3(rel.x,0,rel.z).scale((walking?.2:.06+.04*planted)*Math.min(1,strength));
   // Ankles: with both feet down, the body is drawn back over the middle of its feet. Without it a ragdoll that
   // had caught itself kept leaning a little, crept off at walking pace and never lowered its arms.
   if(stepLeg<0&&!walking){Vec3 ankle=new Vec3(support.x-com.x,0,support.z-com.z).scale(ANKLE*Math.min(1,strength));if(ankle.length()>ANKLE_CAP)ankle=ankle.normalize().scale(ANKLE_CAP);brake=brake.subtract(ankle);}
   for(int i=0;i<6;i++)if(i<4||i-4==stepLeg)parts.get(i).velocity=parts.get(i).velocity.subtract(brake);}
  Vec3 lean=upset.length()>1e-6?upset.normalize().scale(-Math.min(.2,upset.length()*.5)):Vec3.ZERO;
  // A walker leans a little into its stride.
  if(walking)lean=lean.add(desired.normalize().scale(.08));
  // Thrown off balance it tenses up: the trunk is held upright harder, so a blow to the chest or head rocks it
  // instead of folding it over.
  turnUp(torso,new Vec3(0,1,0).add(lean).normalize(),(.45+.15*alarm)*strength);
  // Keep facing the same way: a free twist let every shove turn the ragdoll and it walked off sideways.
  {Vec3 f=flat(torso.vector(new Vec3(0,0,1)));double yaw=Math.atan2(f.x,f.z);if(Double.isNaN(heading))heading=yaw;
   double error=Math.atan2(Math.sin(heading-yaw),Math.cos(heading-yaw));Vec3 u=torso.vector(new Vec3(0,1,0));double spin=torso.angular.dot(u);
   // A walker holds its course harder: each stride twisted it a little and it drifted round to walk sideways.
   torso.angular=torso.angular.add(u.scale((error*(walking?.5:.25)*strength-spin)*.6));}
  // Standing height: the hips sank between the legs while stepping, as if it crawled on its knees.
  {double sag=support.y+rig.center[1].y-torso.position.y;if(sag>.03*scale){double lift=Math.min(.05,sag*.3);for(int i=0;i<4;i++)parts.get(i).velocity=parts.get(i).velocity.add(0,lift,0);}}
  turnUp(parts.get(0),new Vec3(0,1,0),.25*strength);pivot(parts.get(0),torso,torso.position.add(torso.vector(rig.torsoAnchor[0])));
  // Arms balance: the harder the body is thrown off its feet, the further they fly out to the sides and against the
  // fall (forward when falling back), the way people windmill to catch themselves. The alarm rises at once and
  // fades over about a second, with a dead zone: a ragdoll that had caught itself kept a small lean and stood frozen
  // with its arms out. Calm, the arms are held firmly at the sides instead of dangling.
  double wanted=Math.clamp((upset.length()-.14*scale)/(.3*scale),0,1);if(stepLeg>=0&&!walking&&upset.length()>.25*scale)wanted=Math.max(wanted,.3);
  alarm=wanted>alarm?alarm+(wanted-alarm)*.6:alarm*.85;if(alarm<.02)alarm=0;
  Vec3 against=upset.length()>1e-6?upset.normalize().scale(-1):Vec3.ZERO;
  for(int k=0;k<2;k++){double side=rig.torsoAnchor[2+k].x>0?1:-1;var arm=parts.get(2+k);
   // Held a little out from the body, the way a person stands ready; walking, each arm swings against its leg.
   double swing=walking&&stepLeg>=0?(Math.signum(rig.torsoAnchor[4+stepLeg].x)==side?-.35:.35):0;
   Vec3 rest=new Vec3(0,-1,0).add(front.scale(.06+swing)).add(flatForward.scale(side*ARMS));
   Vec3 reach=rest.scale(1-alarm).add(flatForward.scale(side*.9).add(against.scale(.8)).add(0,.35,0).scale(alarm));
   if(reach.lengthSqr()<1e-6)reach=new Vec3(0,-1,0);
   turnUp(arm,reach.normalize().scale(-1),(.5+.3*alarm)*strength);relaxTwist(arm,torso,.4);pivot(arm,torso,torso.position.add(torso.vector(rig.torsoAnchor[2+k])));}
 }
 private double alarm,getUpFrom,groundY;private int calm,downTicks,getUp,gathers,struggle;
 /** Counts ticks spent in a bad pose (decaying while it is fine); true once it has lasted a second. */
 private boolean stuck(boolean bad){struggle=bad?struggle+1:Math.max(0,struggle-2);return struggle>20;}
 /** Gives up balancing: limp for a moment, then {@link #standUp} gets it back on its feet. */
 private void fallDown(){fallTicks=0;fallen=true;struggle=0;stepLeg=-1;stepTicks=0;hold[0]=hold[1]=null;downTicks=10;getUp=0;alarm=0;}
 private static final int GET_UP_TICKS=56;

 private Vec3 walkGoal;private double walkStop=.5,walkSpeed=.05;private boolean bot;
 /**
  * Sends an active ragdoll walking on its own legs to a point (null stops it); it stops within stopDistance of it.
  * It turns toward the goal first, then walks at up to speed blocks per tick (0.05 is a stroll), keeping its balance
  * as it goes: shoved or hit it stumbles, catches itself and walks on, or falls, gets up and walks on.
  */
 private int arriving,settling;private boolean atGoal;
 public void walkTo(Vec3 goal,double stopDistance,double speed){if(goal==null||walkGoal==null||goal.distanceToSqr(walkGoal)>.01){arriving=0;atGoal=false;compose=0;}walkGoal=goal;walkStop=Math.max(.3,stopDistance);walkSpeed=Math.clamp(speed,0,.12);}
 public Vec3 walkGoal(){return walkGoal;}
 /**
  * Standing at its walk goal. It keeps the spot: wandering more than half a block further off (a shove, a slow
  * drift on its feet) it walks back.
  */
 public boolean atGoal(){return walkGoal!=null&&atGoal;}
 /** A bot is an active ragdoll that walks after the nearest player and stops a few blocks short, facing them. */
 public boolean bot(){return bot;}
 public void setBot(boolean value){bot=value;if(!value)walkGoal=null;}
 /** Spawns as a test bot: a default-skinned player ragdoll named "Bot", standing on its feet at feet. */
 public void initializeBot(Vec3 feet,float yaw){
  owner=new GameProfile(UUID.randomUUID(),"Bot");setPos(feet.add(0,rig.center[1].y,0));ensure();active=true;bot=true;
  place(yaw,false,Vec3.ZERO,feet.y);setCustomName(net.minecraft.network.chat.Component.literal("Bot"));setCustomNameVisible(true);publish();
 }
 private void followPlayer(){
  Player near=null;double best=32*32;for(var p:level().players())if(!p.isSpectator()&&p.isAlive()){double d=p.distanceToSqr(this);if(d<best){best=d;near=p;}}
  if(near==null){walkGoal=null;return;}Vec3 to=near.position().subtract(position());
  // A little hysteresis: it sets off once the player is 3.5 blocks away and stops 2.5 short of them.
  if(to.horizontalDistance()>3.5||walkGoal!=null&&!atGoal)walkTo(near.position(),2.5,.05);
  if((walkGoal==null||atGoal)&&!fallen&&to.horizontalDistance()>.1)heading=Math.atan2(to.x,to.z);
 }
 /** Walking velocity toward the goal (zero when there is none, it has arrived, or it is still turning to face it). */
 private Vec3 walkVelocity(Vec3 com,RigidBodyState torso,double scale){
  if(walkGoal==null||walkSpeed<=0)return Vec3.ZERO;Vec3 to=new Vec3(walkGoal.x-com.x,0,walkGoal.z-com.z);double dist=to.length();
  to=to.scale(1/dist);
  // Arriving it stays in its walking gait (a near-zero walking velocity) until it has come to rest: dropped straight
  // into standing balance mid-stride, the stance pull and the strides fought and it stumbled off and fell.
  // (It keeps its heading then: turned to the goal it was standing on, it spun round and shuffled off backwards.)
  if(atGoal&&dist<walkStop+.5)return Vec3.ZERO;
  if(dist<walkStop||arriving>0){Vec3 v=new Vec3(torso.velocity.x,0,torso.velocity.z);if(++arriving>40||v.length()<.012&&stepLeg<0){atGoal=true;arriving=0;gathers=0;settling=80;
   // Stopping, it faces the way its feet point: kept on the walking heading, a torso turned against the legs' twist
   // never came to rest and it went on stepping round its spot.
   Vec3 legs=flat(parts.get(4).vector(new Vec3(0,0,1))).add(flat(parts.get(5).vector(new Vec3(0,0,1))));heading=legs.lengthSqr()<1e-6?Double.NaN:Math.atan2(legs.x,legs.z);beginCompose(foot(0).add(foot(1)).scale(.5));return Vec3.ZERO;}return to.scale(1e-3);}
  atGoal=false;heading=Math.atan2(to.x,to.z);sleeping=false;quiet=0;
  // Turned away it still walks, slower, while it turns: waiting to face the goal first, a walker that had been
  // twisted sideways stood stepping on the spot.
  double facing=flat(torso.vector(new Vec3(0,0,1))).dot(to);
  return to.scale(walkSpeed*scale*Math.clamp(.5+facing*.5,.4,1)*Math.clamp((dist-walkStop+.2)/2.5,.6,1));
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
 /**
 * Getting up after a fall: once it has lain still for a moment, the ragdoll draws its legs under the hips, pushes off
 * with its arms and rises upright over two seconds, then balances again. Held in the air it waits until it lands.
 */
 private void standUp(double strength){
  var torso=parts.get(1);double scale=rig.center[1].y/1.125;
  double lowest=Double.MAX_VALUE;for(var p:parts)lowest=Math.min(lowest,CompoundCollision.bounds(p.box()).minY);
  boolean ground=level().getBlockCollisions(this,new AABB(torso.position.x-.6,lowest-.15,torso.position.z-.6,torso.position.x+.6,lowest+.02,torso.position.z+.6)).iterator().hasNext();
  if(getUp==0){
   fallTicks++;
   // Going down it throws its arms out toward the ground it falls on, to catch itself, and lets them go limp only
   // once it has landed. Lying there, its arms ease back to their natural turn instead of staying wrung round.
   Vec3 fall=new Vec3(torso.velocity.x,0,torso.velocity.z);
   for(int k=0;k<2;k++){var arm=parts.get(2+k);Vec3 shoulder=torso.position.add(torso.vector(rig.torsoAnchor[2+k]));
    if(fallTicks<14&&fall.length()>.01){turnUp(arm,fall.normalize().scale(.8).add(0,-1,0).normalize().scale(-1),.5*strength);pivot(arm,torso,shoulder);}
    else relaxTwist(arm,torso,.15);}
   for(int k=0;k<2;k++)relaxTwist(parts.get(4+k),torso,.1);
   double motion=0;for(var p:parts)motion=Math.max(motion,p.velocity.length()+p.angular.length()*.3);
   downTicks=ground&&motion<.04?downTicks+1:0;if(downTicks<25)return;
   getUp=1;getUpFrom=torso.position.y;groundY=lowest;sleeping=false;quiet=0;
  }
  if(getUp==1)beginGetUp(lowest);
  if(!ground&&getUp<GET_UP_TICKS/2){getUp=0;downTicks=0;return;}
  getUp++;
  // Guided get-up: every limb is steered (by its velocities, so it still collides and pushes things) along a blend
  // from the pose it lay in to its standing pose, the body turning upright first and rising after. Pushing the limbs
  // about by hand it often got only half way up: kneeling, sitting on splayed legs, or skating along half risen.
  double t=Math.min(1,getUp/(double)GET_UP_TICKS),next=Math.min(1,(getUp+1)/(double)GET_UP_TICKS);
  double g=PhysicalistConfig.defaults().gravity();double worst=0;
  // It rises the way people do, through a middle pose: lying on its back it sits up first (legs out in front) and
  // then gets onto its feet; face down it pushes up into a crouch on its hands and rises from there. Blended
  // straight from lying to standing it floated up like a plank on a hinge.
  for(int i=0;i<6;i++){var p=parts.get(i);Vec3 stand=standAt.add(rotated(standTurn,rig.center[i]));
   Vec3 want=next<.5?fromPos[i].lerp(midPos[i],smooth((next-.05)/.45)):midPos[i].lerp(stand,smooth((next-.5)/.5));
   // Muscles, not strings: each limb can only change its speed so much in a tick (about what the body's weight
   // plus a firm push allows), so it rises with effort, carries its own momentum and sways, instead of gliding along
   // the pose like an animation.
   Vec3 v=limited(want.subtract(p.position).scale(.5).add(0,g,0),.3*Math.max(1,strength));p.velocity=p.velocity.add(limited(v.subtract(p.velocity),g+.04*strength));
   Quaternionf aim=next<.5?new Quaternionf(fromQ[i]).slerp(midQ[i],(float)smooth(next/.4)):new Quaternionf(midQ[i]).slerp(standTurn,(float)smooth((next-.5)/.45));
   Quaternionf err=new Quaternionf(aim).mul(new Quaternionf(p.orientation).conjugate());if(err.w<0)err.set(-err.x,-err.y,-err.z,-err.w);
   double angle=2*Math.acos(Math.min(1,err.w)),sin=Math.sqrt(Math.max(1e-12,1-err.w*err.w));
   Vec3 spin=angle<1e-4?Vec3.ZERO:limited(new Vec3(err.x/sin,err.y/sin,err.z/sin).scale(angle*.4),.5);p.angular=p.angular.add(limited(spin.subtract(p.angular),.07*Math.max(1,strength)));
   if(t>=1)worst=Math.max(worst,p.position.distanceTo(stand));}
  // Standing: handed over to balance a few ticks after the blend ends, standing still and facing where it rose.
  if(getUp>=GET_UP_TICKS+6&&(worst<.25*rig.center[1].y||getUp>=GET_UP_TICKS+15&&torso.vector(new Vec3(0,1,0)).y>.95)){
   for(var p:parts){p.velocity=Vec3.ZERO;p.angular=Vec3.ZERO;}
   var f=standTurn.transform(new org.joml.Vector3f(0,0,1));
   fallen=false;getUp=0;downTicks=0;stepLeg=-1;stepTicks=0;calm=0;heading=Math.atan2(f.x,f.z);alarm=0;struggle=0;hold[0]=hold[1]=null;
   // Risen, it settles into its normal stance at once instead of balancing on from wherever the rise left its limbs.
   composeCool=0;beginCompose(foot(0).add(foot(1)).scale(.5));
  }else if(getUp>GET_UP_TICKS+40){getUp=0;downTicks=0;}
 }
 private final Vec3[] footAt=new Vec3[2];private static final int COMPOSE_TICKS=36;private Vec3 shoveDir=Vec3.ZERO;private int shoveTicks,compose,composeCool,composeSteps;private double restless;
 /** Starts straightening up into the rest stance over the given point between the feet, facing the legs' way. */
 private void beginCompose(Vec3 support){
  Vec3 legs=flat(parts.get(4).vector(new Vec3(0,0,1))).add(flat(parts.get(5).vector(new Vec3(0,0,1))));
  double yaw=legs.lengthSqr()<1e-6?(Double.isNaN(heading)?0:heading):Math.atan2(legs.x,legs.z);
  // Square to its feet: facing across the line between them, so each hip comes right over its own foot. Faced along
  // the legs with one foot ahead, the hips pulled the feet round and they slid off their spots.
  {Vec3 across=foot(1).subtract(foot(0));across=new Vec3(across.x,0,across.z);
   if(across.length()>.1){Vec3 f=new Vec3(across.z,0,-across.x).normalize();if(f.dot(new Vec3(Math.sin(yaw),0,Math.cos(yaw)))<0)f=f.scale(-1);
    double square=Math.atan2(f.x,f.z),d=Math.atan2(Math.sin(square-yaw),Math.cos(square-yaw));yaw+=Math.clamp(d,-.35,.35);}}
  double lowest=Double.MAX_VALUE;for(var p:parts)lowest=Math.min(lowest,CompoundCollision.bounds(p.box()).minY);
  standTurn=new Quaternionf().rotationY((float)yaw);standAt=new Vec3(support.x,lowest+.005,support.z);
  for(int i=0;i<6;i++){fromPos[i]=parts.get(i).position;fromQ[i]=new Quaternionf(parts.get(i).orientation);}
  for(int k=0;k<2;k++)footAt[k]=foot(k);
  compose=1;composeCool=100;heading=yaw;stepLeg=-1;stepTicks=0;hold[0]=hold[1]=null;sleeping=false;quiet=0;
 }
 /**
  * Straightening up: every limb is steered along a short blend into its rest pose (as the get-up does), so the body
  * ends upright right over its feet with its arms at its sides and nothing twisted, then balances from there. The
  * feet stay where they stand (any foot off its place was stepped there first): dragged into the rest stance they
  * skated over the floor. Each leg just straightens up over its own foot.
  */
 private void compose(double g,double strength){
  double next=Math.min(1,(compose+1)/(double)COMPOSE_TICKS),k=smooth(next);
  for(int i=0;i<6;i++){var p=parts.get(i);
   Vec3 stand=i>=4?new Vec3(footAt[i-4].x,standAt.y+rig.center[i].y,footAt[i-4].z):standAt.add(rotated(standTurn,rig.center[i]));
   p.velocity=limited(fromPos[i].lerp(stand,k).subtract(p.position).scale(.7).add(0,g,0),.2);
   Quaternionf aim=new Quaternionf(fromQ[i]).slerp(standTurn,(float)k);
   Quaternionf err=new Quaternionf(aim).mul(new Quaternionf(p.orientation).conjugate());if(err.w<0)err.set(-err.x,-err.y,-err.z,-err.w);
   double angle=2*Math.acos(Math.min(1,err.w)),sin=Math.sqrt(Math.max(1e-12,1-err.w*err.w));
   p.angular=angle<1e-4?Vec3.ZERO:limited(new Vec3(err.x/sin,err.y/sin,err.z/sin).scale(angle*.5),.4);}
  if(++compose>COMPOSE_TICKS+2){compose=0;for(var p:parts){p.velocity=Vec3.ZERO;p.angular=Vec3.ZERO;}calm=0;alarm=0;struggle=0;}
 }
 private Vec3 standAt=Vec3.ZERO;private Quaternionf standTurn=new Quaternionf();private final Vec3[] fromPos=new Vec3[6];private final Quaternionf[] fromQ=new Quaternionf[6];
 /**
  * Picks the standing pose to rise into: over the middle of the body, turned the shortest way upright (lying on its
  * back it ends facing where its feet were, face down facing where its head was, as people sit or push up).
  */
 private void beginGetUp(double floor){
  var torso=parts.get(1);Vec3 up=torso.vector(new Vec3(0,1,0));
  Quaternionf upright=new Quaternionf().rotationTo((float)up.x,(float)up.y,(float)up.z,0,1,0).mul(new Quaternionf(torso.orientation));
  var f=upright.transform(new org.joml.Vector3f(0,0,1));double yaw=Math.hypot(f.x,f.z)<1e-4?0:Math.atan2(f.x,f.z);
  standTurn=new Quaternionf().rotationY((float)yaw);
  double total=0;Vec3 com=Vec3.ZERO;for(int i=0;i<6;i++){com=com.add(parts.get(i).position.scale(rig.mass[i]));total+=rig.mass[i];}com=com.scale(1/total);
  for(int i=0;i<6;i++){fromPos[i]=parts.get(i).position;fromQ[i]=new Quaternionf(parts.get(i).orientation);}
  // Middle pose, in the frame of the spot it gets up on (the torso's spot on the floor, facing the way it will stand).
  boolean faceDown=torso.vector(new Vec3(0,0,1)).y<0;
  Quaternionf trunk=new Quaternionf().rotationX(faceDown?1.3F:.25F),leg=new Quaternionf().rotationX(faceDown?-.6F:(float)(-Math.PI/2));
  var legBox=parts.get(4).half;double legLen=2*legBox.y;
  Vec3 hipOffset=rotated(trunk,rig.torsoAnchor[4]);Vec3 legDir=rotated(leg,new Vec3(0,-1,0));
  // Sitting the legs lie on the floor; crouched the feet stand on it.
  double hipY=faceDown?-legDir.y*legLen:legBox.z;Vec3 trunkAt=new Vec3(0,hipY-hipOffset.y,0);
  Vec3[] local=new Vec3[6];Quaternionf[] localQ=new Quaternionf[6];Vec3 feet=Vec3.ZERO;
  for(int i=0;i<4;i++){local[i]=trunkAt.add(rotated(trunk,rig.center[i].subtract(rig.center[1])));localQ[i]=new Quaternionf(trunk);}
  for(int k=0;k<2;k++){Vec3 hip=trunkAt.add(rotated(trunk,rig.torsoAnchor[4+k]));local[4+k]=hip.add(legDir.scale(legLen/2));localQ[4+k]=new Quaternionf(leg);feet=feet.add(hip.add(legDir.scale(legLen)).scale(.5));}
  // Crouched it leans on its hands: the arms hang straight down from the shoulders to the floor.
  if(faceDown)for(int k=0;k<2;k++){Vec3 shoulder=trunkAt.add(rotated(trunk,rig.torsoAnchor[2+k]));local[2+k]=shoulder.add(0,-parts.get(2+k).half.y,0);localQ[2+k]=new Quaternionf();}
  Vec3 at=new Vec3(torso.position.x,floor+.01,torso.position.z);
  for(int i=0;i<6;i++){midPos[i]=at.add(rotated(standTurn,local[i]));midQ[i]=new Quaternionf(standTurn).mul(localQ[i]);}
  // It stands up over where its feet were in the middle pose.
  Vec3 f2=rotated(standTurn,new Vec3(feet.x,0,feet.z));standAt=at.add(f2);
 }
 private final Vec3[] midPos=new Vec3[6];private final Quaternionf[] midQ=new Quaternionf[6];private int fallTicks;
 /** Eases a limb's turn about its own length back toward the body's (arms and legs left wrung round after a fall). */
 private static void relaxTwist(RigidBodyState part,RigidBodyState body,double gain){
  Vec3 u=part.vector(new Vec3(0,1,0)),f=part.vector(new Vec3(0,0,1)),ref=body.vector(new Vec3(0,0,1));ref=ref.subtract(u.scale(ref.dot(u)));
  if(ref.lengthSqr()<1e-4)return;ref=ref.normalize();double e=Math.atan2(f.cross(ref).dot(u),f.dot(ref));
  part.angular=part.angular.add(u.scale((e*gain-part.angular.dot(u))*.3));
 }
 private static double smooth(double x){x=Math.clamp(x,0,1);return x*x*(3-2*x);}
 private static Vec3 rotated(Quaternionf q,Vec3 v){var r=q.transform(new org.joml.Vector3f((float)v.x,(float)v.y,(float)v.z));return new Vec3(r.x,r.y,r.z);}
 /**
  * A limb turned by the controller swings about its joint, not its own middle: its centre moves with the joint.
  * Turning it about the middle tore the hip apart (legs drawn away from the body) until the joint pulled it back.
  */
 private static void pivot(RigidBodyState limb,RigidBodyState torso,Vec3 joint){
  limb.velocity=torso.pointVelocity(joint).add(limb.angular.cross(limb.position.subtract(joint)));
 }
 /** Spins a limb about its own long axis so its front turns toward a flat direction. */
 private static void twistToward(RigidBodyState part,Vec3 front,double gain){
  Vec3 u=part.vector(new Vec3(0,1,0)),f=flat(part.vector(new Vec3(0,0,1)));double e=Math.atan2(f.z*front.x-f.x*front.z,f.dot(front));
  part.angular=part.angular.add(u.scale((e*gain-part.angular.dot(u))*.5));
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
  if(++age>=PhysicalistConfig.RAGDOLL_LIFETIME.get()*20&&!bot){discard();return;}
  if(getDeltaMovement().lengthSqr()>1e-8){for(var p:parts)p.velocity=p.velocity.add(getDeltaMovement());setDeltaMovement(Vec3.ZERO);sleeping=false;quiet=0;}
  thrownItems();
  if(bot&&active&&tickCount%10==0)followPlayer();
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
 @Override protected void addAdditionalSaveData(CompoundTag tag){ensure();tag.put("Ragdoll",state());tag.put("Mob",mob);tag.putBoolean("Active",active);tag.putBoolean("Fallen",fallen);tag.putBoolean("Bot",bot);}
 @Override protected void readAdditionalSaveData(CompoundTag tag){setMob(tag.getCompound("Mob"));readState(tag.getCompound("Ragdoll"),false);active=tag.getBoolean("Active");fallen=tag.getBoolean("Fallen");bot=tag.getBoolean("Bot");publish();}
 @Override public boolean isPickable(){return true;}
 /** A hit shoves the ragdoll away from the attacker, hardest at the limb that was hit. */
 @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source,float amount){
  if(level().isClientSide||isInvulnerableTo(source))return false;ensure();Entity by=source.getEntity(),direct=source.getDirectEntity();
  // An arrow or a snowball pushes along its flight, not along where its shooter happens to look now.
  boolean flying=direct!=null&&direct!=by&&direct.getDeltaMovement().lengthSqr()>1e-4;
  Vec3 direction=flying?direct.getDeltaMovement():by instanceof LivingEntity living?living.getLookAngle():by!=null?position().subtract(by.position()):new Vec3(0,1,0);
  direction=new Vec3(direction.x,Math.max(direction.y,0)*.3+.05,direction.z);if(!RigidBodyState.finite(direction)||direction.lengthSqr()<1e-8)direction=new Vec3(0,1,0);
  // A blow moves a body far less than it moves a block: a punch rocks a person back a step, a heavy blow sends them
  // staggering. (Passive ragdolls fly further: nobody braces them.) It used to throw the whole body at over two metres
  // a second for a bare fist and the ragdoll went straight down.
  double size=active&&!fallen?.04+.011*amount:.14+.02*amount;
  Vec3 push=direction.normalize().scale(Math.min(active&&!fallen?.2:.5,size));int hit=1;
  if(flying){Vec3 from=direct.position().subtract(direct.getDeltaMovement().scale(2)),to=direct.position().add(direct.getDeltaMovement().scale(2));double nearest=Double.POSITIVE_INFINITY;for(int i=0;i<parts.size();i++){double f=PhysicalistTargeting.rayFraction(parts.get(i).box(),from,to);if(f<nearest){nearest=f;hit=i;}}}
  else if(by instanceof Player player){Vec3 eye=player.getEyePosition(),end=eye.add(player.getLookAngle().scale(8));double nearest=Double.POSITIVE_INFINITY;for(int i=0;i<parts.size();i++){double f=PhysicalistTargeting.rayFraction(parts.get(i).box(),eye,end);if(f<nearest){nearest=f;hit=i;}}}
  boolean braced=active&&!fallen;for(int i=0;i<parts.size();i++)parts.get(i).velocity=parts.get(i).velocity.add(push.scale(i==hit?(braced?1.1:1.6):(braced?1:.8)));
  // Shoved while standing it steps back with the blow, as a person does, instead of bending over at the waist.
  if(braced){shoveDir=new Vec3(push.x,0,push.z);shoveTicks=8;compose=0;}sleeping=false;quiet=0;return true;
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
