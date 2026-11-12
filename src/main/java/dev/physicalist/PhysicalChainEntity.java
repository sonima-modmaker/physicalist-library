package dev.physicalist;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;
import org.joml.Quaternionf;
/** A saved articulated chain; cuts remove joints, not visual-only segments. */
public final class PhysicalChainEntity extends Entity implements PhysicalistBodyProvider,PhysicalistDragTarget {
 private static final EntityDataAccessor<CompoundTag> POSE=SynchedEntityData.defineId(PhysicalChainEntity.class,EntityDataSerializers.COMPOUND_TAG);
 private final List<RigidBodyState> links=new ArrayList<>();private final BitSet cuts=new BitSet();
 private ChainSableBridge.Rope sableRope;
 private final List<BodyPoseBuffer> clientPoses=new ArrayList<>();
 private ChainEndpoint first,last;private Vec3 a,b;private Vec3[] previous=new Vec3[0];private Quaternionf[] previousQ=new Quaternionf[0];private long revision;private final Vec3[] anchorSim=new Vec3[2];
 public PhysicalChainEntity(EntityType<? extends PhysicalChainEntity> type,Level level){super(type,level);noPhysics=true;noCulling=true;setNoGravity(true);}
 @Override protected void defineSynchedData(SynchedEntityData.Builder builder){builder.define(POSE,new CompoundTag());}
 public List<RigidBodyState> links(){return links;}public long revision(){return revision;}public boolean cut(int i){return cuts.get(i);}public ChainEndpoint endpoint(int i){return i==0?first:last;}public Vec3 anchor(int i){return i==0?a:b;}
 public boolean connected(){return cuts.isEmpty();}public double length(){double total=0;for(var link:links)total+=link.half.y*2;return total;}
 @Override public void remove(RemovalReason reason){ChainSableBridge.close(sableRope);sableRope=null;super.remove(reason);}
 public void initialize(ChainEndpoint first,ChainEndpoint last){
  this.first=first;this.last=last;var level=(ServerLevel)level();a=first.resolve(level);b=last.resolve(level);
  if(a==null||b==null)throw new IllegalArgumentException("Attachment is not loaded");double length=a.distanceTo(b);if(length<.3||length>32)throw new IllegalArgumentException("Chain length must be 0.3-32 blocks");
  int count=Math.min(65,(int)Math.ceil(length/.5)+1);double half=length/count*.54;var joints=sagLayout(a,b,count,half*2);
  // Links start already sagging with their joints closed; a straight, compressed start buckled violently.
  for(int i=0;i<count;i++){Vec3 from=joints[i],to=joints[i+1],direction=to.subtract(from).normalize();var link=new RigidBodyState(from.add(to).scale(.5),new Vec3(.05,half,.05),.4);link.orientation.rotationTo(0,1,0,(float)direction.x,(float)direction.y,(float)direction.z);links.add(link);}refresh();snapshot();publish();
 }
 /** Joint points on a parabolic sag whose arc length equals the chain length, resampled to equal segments. */
 static Vec3[] sagLayout(Vec3 a,Vec3 b,int count,double segment){
  Vec3 span=b.subtract(a),u=span.normalize();Vec3 down=new Vec3(0,-1,0).add(u.scale(u.y));if(down.lengthSqr()<1e-6)down=new Vec3(1,0,0).subtract(u.scale(u.x));down=down.normalize();
  final int samples=512;double target=count*segment,lo=0,hi=target;Vec3[] curve=new Vec3[samples+1];double[] arc=new double[samples+1];
  for(int iter=0;iter<48;iter++){double sag=(lo+hi)*.5;sagCurve(a,span,down,sag,curve,arc);if(arc[samples]<target)lo=sag;else hi=sag;}
  sagCurve(a,span,down,lo,curve,arc);Vec3[] out=new Vec3[count+1];out[0]=a;out[count]=b;int at=0;
  for(int k=1;k<count;k++){double want=arc[samples]*k/count;while(at<samples-1&&arc[at+1]<want)at++;double f=(want-arc[at])/Math.max(1e-12,arc[at+1]-arc[at]);out[k]=curve[at].lerp(curve[at+1],Math.clamp(f,0,1));}
  return out;
 }
 private static void sagCurve(Vec3 a,Vec3 span,Vec3 down,double sag,Vec3[] curve,double[] arc){int n=curve.length-1;for(int i=0;i<=n;i++){double t=(double)i/n;curve[i]=a.add(span.scale(t)).add(down.scale(sag*4*t*(1-t)));arc[i]=i==0?0:arc[i-1]+curve[i].distanceTo(curve[i-1]);}}
 /** Native kinematic anchors are driven by velocity toward the attachment instead of being teleported every tick. */
 public Vec3 nativeAnchor(int end){Vec3 target=anchor(end),sim=anchorSim[end];return sim==null||sim.distanceToSqr(target)>16?target:sim;}
 public void nativeAnchorMoved(int end,Vec3 p){anchorSim[end]=p;}
 private void snapshot(){previous=new Vec3[links.size()];previousQ=new Quaternionf[links.size()];for(int i=0;i<links.size();i++){previous[i]=links.get(i).position;previousQ[i]=new Quaternionf(links.get(i).orientation);}}
 public Vec3 visualPosition(int i,float partial){return previous[i].lerp(links.get(i).position,partial);}public Quaternionf visualRotation(int i,float partial){return new Quaternionf(previousQ[i]).slerp(links.get(i).orientation,partial);}
 @Override public void tick(){super.tick();if(links.isEmpty())return;snapshot();if(level().isClientSide){var tag=entityData.get(POSE);read(tag,true);for(int i=0;i<links.size();i++){var pose=clientPoses.get(i).at(level().getGameTime()-2);links.get(i).position=pose.position();links.get(i).orientation.set(pose.rotation());}refresh();return;}
  if(getDeltaMovement().lengthSqr()>1e-10){for(var link:links)link.velocity=link.velocity.add(getDeltaMovement());setDeltaMovement(Vec3.ZERO);}
  var level=(ServerLevel)level();Vec3 nextA=first.resolve(level),nextB=last.resolve(level);if(nextA==null||nextB==null)return;a=nextA;b=nextB;tether(level);
  sableRope=ChainSableBridge.updateRope(level,this,sableRope);
  if(!dev.physicalist.nativecore.NativeWorlds.enqueue(this))fallback();
 }
 public void nativeStepFinished(){refresh();publish();}
 /** Taut length for loose ends; a little under the link total so the links never carry the holder's weight. */
 private double tetherLength(){return length()*.97;}
 /**
  * Loose entity ends (mobs) are held like a rope: nothing while the chain hangs slack; once taut the end is moved
  * back onto the rope sphere and loses only its outward speed, each end by its mass share. Nothing is ever added, so
  * a falling holder stops instead of being kicked back up, and two free ends fall together instead of lifting off.
  * Players are held by their own client (see {@link #holdLocalPlayer}): their motion is client-owned, and server
  * velocity packets arrived ticks late and made a caught player hop.
  */
 private void tether(ServerLevel level){
  if(!connected())return;
  for(int end=0;end<2;end++){var self=endpoint(end);if(!self.loose())continue;Entity e=self.entity(level);if(e==null||!e.isAlive())continue;
   Entity other=endpoint(1-end).entity(level);Vec3 at=anchor(end),to=anchor(1-end);
   if(e instanceof net.minecraft.server.level.ServerPlayer){if(at.distanceTo(to)>tetherLength())e.resetFallDistance();continue;}
   if(hold(e,at,to,other==null?Vec3.ZERO:other.position().subtract(other.xo,other.yo,other.zo),share(e,other),tetherLength())){Vec3 moved=self.resolve(level);if(moved!=null){if(end==0)a=moved;else b=moved;}}
  }
 }
 /** Fraction of the stretch the end holding {@code self} takes; the rest belongs to the other end. */
 private static double share(Entity self,Entity other){double ms=ChainEndpoint.mass(self),mo=ChainEndpoint.mass(other);return Double.isInfinite(mo)?1:mo/(ms+mo);}
 /**
  * Moves {@code e} (whose chain point is {@code at}) back toward the rope sphere around {@code to} by its share of
  * the overstretch and removes its share of the separating speed. Returns whether the rope was taut.
  */
 static boolean hold(Entity e,Vec3 at,Vec3 to,Vec3 otherVelocity,double share,double limit){
  Vec3 span=at.subtract(to);double distance=span.length();if(distance<=limit||distance<1e-6)return false;
  Vec3 n=span.scale(1/distance);Vec3 fix=n.scale(-(distance-limit)*share);
  if(fix.lengthSqr()>1e-10)e.move(net.minecraft.world.entity.MoverType.SELF,fix);
  Vec3 v=e.getDeltaMovement();double outward=v.dot(n)-otherVelocity.dot(n);if(outward>0)e.setDeltaMovement(v.subtract(n.scale(outward*share)));
  e.resetFallDistance();return true;
 }
 /**
  * Client side of the tether for the local player, run after its own movement and before the position is sent:
  * the same rope rule as mobs, applied where the player's motion actually lives.
  */
 public static void holdLocalPlayer(Player player){
  var level=player.level();for(var chain:level.getEntitiesOfClass(PhysicalChainEntity.class,player.getBoundingBox().inflate(40),c->c.first!=null&&c.connected()&&c.attachedTo(player.getUUID()))){
   for(int end=0;end<2;end++){var self=chain.endpoint(end);if(!self.loose()||!self.data.getUUID("Entity").equals(player.getUUID()))continue;
    var otherEnd=chain.endpoint(1-end);Entity other=otherEnd.data.hasUUID("Entity")?chain.clientEnd(1-end):null;if(otherEnd.data.hasUUID("Entity")&&other==null)continue;
    Vec3 at=self.resolveClient(level,player,1),to=otherEnd.resolveClient(level,other,1);if(at==null||to==null)continue;
    hold(player,at,to,other==null?Vec3.ZERO:other.position().subtract(other.xo,other.yo,other.zo),share(player,other),chain.tetherLength());
   }
  }
 }
 private Entity clientEnd(int end){var holder=clientEnds[end];if(holder==null||holder.isRemoved())holder=clientEnds[end]=endpoint(end).findNear(level(),getBoundingBox());return holder;}
 private final Entity[] clientEnds=new Entity[2];
 /**
  * Link start/end points drawn this frame. Buffered physics runs ticks behind its holders, so attached ends are pinned
  * to where the holder is drawn now and the rest relaxes like a rope: only stretched links pull their neighbours.
  * Shifting the whole chain toward its ends made it slide along with a walking player and let go at flying speed.
  */
 public Vec3[][] visualSegments(float partial){
  int n=links.size();Vec3[] start=new Vec3[n],end=new Vec3[n];double[] length=new double[n];
  for(int i=0;i<n;i++){var v=visualRotation(i,partial).transform(new org.joml.Vector3f(0,(float)links.get(i).half.y,0));Vec3 c=visualPosition(i,partial);start[i]=c.subtract(v.x,v.y,v.z);end[i]=c.add(v.x,v.y,v.z);length[i]=links.get(i).half.y*2;}
  // Joint k joins link k-1 and k unless cut; joint 0 and n hold the attachments.
  Vec3[] joint=new Vec3[n+1];boolean[] pinned=new boolean[n+1];
  for(int k=0;k<=n;k++)joint[k]=k==0?start[0]:k==n?end[n-1]:cuts.get(k)?end[k-1]:end[k-1].add(start[k]).scale(.5);
  for(int e=0;e<2&&first!=null;e++){int k=e==0?0:n;if(cuts.get(k))continue;var endpoint=endpoint(e);var holder=clientEnd(e);Vec3 target=endpoint.resolveClient(level(),holder,partial);
   if(target!=null&&target.distanceToSqr(joint[k])<32*32){joint[k]=target;pinned[k]=true;}}
  // A link right after a cut starts at its own free point instead of the shared joint.
  Vec3[] free=new Vec3[n];for(int k=1;k<n;k++)if(cuts.get(k))free[k]=start[k];
  // FABRIK-style sweeps: each pass moves only the joint farther from the end it started at, so a pull travels the
  // whole chain in one pass and fades out where the chain still hangs slack.
  for(int iter=0;iter<24;iter++)for(int s=0;s<n;s++){boolean forward=(iter&1)==0;int i=forward?s:n-1-s;
   Vec3 p=free[i]!=null?free[i]:joint[i],q=joint[i+1];Vec3 d=q.subtract(p);double dist=d.length();if(dist<=length[i])continue;
   boolean lockP=free[i]==null&&pinned[i],lockQ=pinned[i+1];if(lockP&&lockQ)continue;
   double wp=lockP?0:lockQ?1:forward?0:1,wq=1-wp;Vec3 fix=d.scale((dist-length[i])/dist);
   if(free[i]!=null)free[i]=p.add(fix.scale(wp));else joint[i]=p.add(fix.scale(wp));joint[i+1]=q.subtract(fix.scale(wq));}
  Vec3[][] out=new Vec3[n][];for(int i=0;i<n;i++)out[i]=new Vec3[]{free[i]!=null?free[i]:joint[i],joint[i+1]};return out;
 }
 private void refresh(){if(links.isEmpty())return;setPos(links.get(0).position);AABB bounds=CompoundCollision.bounds(links.get(0).box());for(var p:links)bounds=bounds.minmax(CompoundCollision.bounds(p.box()));setBoundingBox(bounds.inflate(.08));}
 public boolean cutAt(int joint){if(joint<0||joint>links.size()||cuts.get(joint))return false;cuts.set(joint);revision++;ChainSableBridge.close(sableRope);sableRope=null;publish();return true;}
 public int nearestLink(Player player){return nearestLink(player,6);}
 public int nearestLink(Player player,double reach){Vec3 eye=player.getEyePosition(),end=eye.add(player.getLookAngle().scale(reach));double best=Double.POSITIVE_INFINITY;int nearest=-1;for(int i=0;i<links.size();i++){double hit=PhysicalistTargeting.rayFraction(links.get(i).box(),eye,end);if(hit<best){best=hit;nearest=i;}}return nearest;}

 // Client poses come from the link buffer; vanilla move packets would shrink the box to one link.
 @Override public void lerpTo(double x,double y,double z,float yaw,float pitch,int steps){if(!level().isClientSide||links.isEmpty())super.lerpTo(x,y,z,yaw,pitch,steps);}
 @Override public AABB getBoundingBoxForCulling(){if(links.isEmpty())return super.getBoundingBoxForCulling();AABB bounds=getBoundingBox();for(var p:previous)if(p!=null)bounds=bounds.minmax(new AABB(p,p));double reach=0;for(var link:links)reach=Math.max(reach,link.half.y);return bounds.inflate(reach+.5);}
 @Override public boolean shouldRenderAtSqrDistance(double distance){double range=128*getViewScale();return distance<range*range;}
 @Override public boolean isPickable(){return true;}
 @Override public InteractionResult interact(Player player,InteractionHand hand){if(!player.getItemInHand(hand).is(Items.SHEARS))return InteractionResult.PASS;if(!level().isClientSide){int hit=nearestLink(player);if(hit<0)return InteractionResult.PASS;int joint=Math.min(links.size(),hit+1);if(cutAt(joint))level().playSound(null,blockPosition(),net.minecraft.sounds.SoundEvents.SHEEP_SHEAR,net.minecraft.sounds.SoundSource.PLAYERS,.8f,.8f);}return InteractionResult.sidedSuccess(level().isClientSide);}
 @Override public InteractionResult interactAt(Player player,Vec3 at,InteractionHand hand){return interact(player,hand);}
 private CompoundTag state(){var t=new CompoundTag();if(first==null)return t;t.putLong("T",level().getGameTime());t.put("First",first.data);t.put("Last",last.data);t.putLongArray("Cuts",cuts.toLongArray());var list=new ListTag();for(var p:links){var c=new CompoundTag();c.putDouble("X",p.position.x);c.putDouble("Y",p.position.y);c.putDouble("Z",p.position.z);c.putFloat("I",p.orientation.x);c.putFloat("J",p.orientation.y);c.putFloat("K",p.orientation.z);c.putFloat("W",p.orientation.w);c.putDouble("H",p.half.y);c.putDouble("VX",p.velocity.x);c.putDouble("VY",p.velocity.y);c.putDouble("VZ",p.velocity.z);c.putDouble("AX",p.angular.x);c.putDouble("AY",p.angular.y);c.putDouble("AZ",p.angular.z);list.add(c);}t.put("Links",list);return t;}
 private void publish(){entityData.set(POSE,state());}
 private void read(CompoundTag tag,boolean client){var list=tag.getList("Links",Tag.TAG_COMPOUND);if(list.isEmpty()||list.size()>65)return;first=new ChainEndpoint(tag.getCompound("First"));last=new ChainEndpoint(tag.getCompound("Last"));cuts.clear();cuts.or(BitSet.valueOf(tag.getLongArray("Cuts")));boolean fresh=links.size()!=list.size();if(fresh){links.clear();clientPoses.clear();}
  for(int i=0;i<list.size();i++){var t=list.getCompound(i);Vec3 p=new Vec3(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z"));if(!RigidBodyState.finite(p))return;double half=Math.clamp(t.getDouble("H"),.005,.3);var q=new Quaternionf(t.getFloat("I"),t.getFloat("J"),t.getFloat("K"),t.getFloat("W"));if(!q.isFinite()||q.lengthSquared()<.5)return;if(fresh)links.add(new RigidBodyState(p,new Vec3(.05,half,.05),.4));var link=links.get(i);if(!client||fresh){link.position=p;link.orientation.set(q.normalize());}link.velocity=new Vec3(t.getDouble("VX"),t.getDouble("VY"),t.getDouble("VZ"));link.angular=new Vec3(t.getDouble("AX"),t.getDouble("AY"),t.getDouble("AZ"));if(client){if(fresh)clientPoses.add(new BodyPoseBuffer());clientPoses.get(i).add(tag.getLong("T"),p,link.velocity,q,link.angular);}}
  refresh();if(fresh)snapshot();
 }
 @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key){super.onSyncedDataUpdated(key);if(POSE.equals(key)&&level().isClientSide)read(entityData.get(POSE),true);}
 @Override protected void addAdditionalSaveData(CompoundTag tag){tag.put("Chain",state());}
 @Override protected void readAdditionalSaveData(CompoundTag tag){read(tag.getCompound("Chain"),false);}
 private final PhysicsBody adapter=new PhysicsBody(){
  public Entity entity(){return PhysicalChainEntity.this;}public java.util.List<CompoundCollision.Box> collisionBoxes(){return links.stream().map(RigidBodyState::box).toList();}public Vec3 forward(){return new Vec3(0,0,1);}public Vec3 up(){return new Vec3(0,1,0);}public Vec3 angularVelocity(){return links.isEmpty()?Vec3.ZERO:links.get(0).angular;}public void setAngularVelocity(Vec3 w){for(var p:links)p.angular=w;}public double aerodynamicMass(){return Math.max(.4,links.size()*.4);}
  public void rotate(Vec3 w,double fraction){double angle=w.length()*fraction;if(angle<1e-10)return;var q=new Quaternionf().rotationAxis((float)angle,(float)(w.x/w.length()),(float)(w.y/w.length()),(float)(w.z/w.length()));for(var p:links){var d=p.position.subtract(position());var v=q.transform(new org.joml.Vector3f((float)d.x,(float)d.y,(float)d.z));p.position=position().add(v.x,v.y,v.z);p.orientation.premul(q);}refresh();}
 };
 public boolean attachedTo(java.util.UUID id){return first!=null&&first.data.hasUUID("Entity")&&first.data.getUUID("Entity").equals(id)||last!=null&&last.data.hasUUID("Entity")&&last.data.getUUID("Entity").equals(id);}
 public void pushAt(Vec3 point,Vec3 velocity){if(links.isEmpty())return;RigidBodyState nearest=links.get(0);for(var link:links)if(link.position.distanceToSqr(point)<nearest.position.distanceToSqr(point))nearest=link;nearest.impulse(velocity.scale(1/nearest.inverseMass),point);}
 @Override public PhysicsBody physicalistBody(){return adapter;}
 /**
  * The wand holds the link it was pointed at when the grab began (by the hand, as it were): only that link is drawn
  * to the wand and the rest of the chain hangs from it. It used to look for a link again every tick (and took the
  * first one when the look missed) and moved the whole chain stiffly along with it.
  */
 @Override public void physicalistDrag(Player player,Vec3 goal){if(links.isEmpty())return;long now=level().getGameTime();
  var grip=grips.get(player.getUUID());if(grip==null||grip[1]<now-2||grip[0]>=links.size()){int hit=nearestLink(player,64);if(hit<0){double best=Double.MAX_VALUE;for(int i=0;i<links.size();i++){double d=links.get(i).position.distanceToSqr(goal);if(d<best){best=d;hit=i;}}}grip=new long[]{hit,now};grips.put(player.getUUID(),grip);}
  grip[1]=now;var held=links.get((int)grip[0]);held.velocity=GrabController.velocity(goal.subtract(held.position),held.velocity,PhysicalistConfig.defaults().gravity());held.angular=held.angular.scale(.5);}
 private final java.util.Map<java.util.UUID,long[]> grips=new java.util.HashMap<>();
 private void fallback(){
  // The same library's rigid-body/XPBD primitives keep chains usable without JNI.
  var anchors=List.of(new RigidBodyState(a,new Vec3(.01,.01,.01),0),new RigidBodyState(b,new Vec3(.01,.01,.01),0));
  var joints=new ArrayList<ElasticJoint>();for(int j=0;j<=links.size();j++){if(cuts.get(j))continue;var left=j==0?anchors.get(0):links.get(j-1);var right=j==links.size()?anchors.get(1):links.get(j);joints.add(ElasticJoint.socket(left,right,j==0?Vec3.ZERO:new Vec3(0,left.half.y,0),j==links.size()?Vec3.ZERO:new Vec3(0,-right.half.y,0),0,.05));}
  for(int sub=0;sub<8;sub++){
   for(var p:links){p.velocity=p.velocity.add(0,-PhysicalistConfig.defaults().gravity()/8,0);p.position=p.position.add(p.velocity.scale(.125));p.turn(p.angular.scale(.125));}
   for(int iter=0;iter<12;iter++)for(var joint:joints)joint.solve(.125);
   for(var p:links)for(var shape:level().getBlockCollisions(this,CompoundCollision.bounds(p.box()).inflate(.02)))for(var obstacle:shape.toAabbs()){var c=CompoundCollision.contact(p.box(),CompoundCollision.box(obstacle));if(c!=null){p.position=p.position.add(c.normal().scale(c.depth()+.001));double closing=p.velocity.dot(c.normal());if(closing<0)p.velocity=p.velocity.subtract(c.normal().scale(closing));}}
   for(var joint:joints)joint.solveVelocity(.9);
  }nativeStepFinished();
 }
}
