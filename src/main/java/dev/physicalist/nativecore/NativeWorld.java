package dev.physicalist.nativecore;
import dev.physicalist.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import org.joml.Quaternionf;
/** One persistent native scene per dimension. Only the server thread reads/writes Minecraft state. */
public final class NativeWorld implements AutoCloseable {
 interface Source {
  Entity owner();int role();Vec3 position();Quaternionf rotation();Vec3 velocity();Vec3 angular();double mass();long revision();double[] geometry();
  default Vec3 center(){return position();}
  default AABB bounds(){return owner().getBoundingBox();}
  default double gravity(){return PhysicalistConfig.defaults().gravity();}
  default AABB ignored(){return null;}
  default void forces(long scene,long id){}void accept(Vec3 p,Quaternionf q,Vec3 v,Vec3 w,boolean sleeping);
  default void impact(int part,CompoundCollision.Contact contact,double speed,double impulse){}
  default void completed(boolean sleeping){}
  /** Density relative to water; NaN opts out of buoyancy. */
  default double waterDensity(){return Double.NaN;}
  /** World bounds per native collision part, in geometry order. */
  default List<AABB> waterParts(){return List.of(bounds());}
 }
 private static final class BodySource implements Source{
  final PhysicsBody body;BodySource(PhysicsBody body){this.body=body;}
  public Entity owner(){return body.entity();}public int role(){return -1;}public Vec3 position(){return owner().position();}public Quaternionf rotation(){return body.nativeOrientation();}
  public Vec3 velocity(){return owner().getDeltaMovement();}public Vec3 angular(){return body.angularVelocity();}public double mass(){return Math.max(.1,body.aerodynamicMass());}public long revision(){return body.nativeGeometryRevision();}
  public Vec3 center(){return body.centerOfMass();}
  public AABB bounds(){AABB result=null;for(var box:body.collisionBoxes()){var b=CompoundCollision.bounds(box);result=result==null?b:result.minmax(b);}return result==null?owner().getBoundingBox():result;}
  public double[] geometry(){return NativeGeometry.boxes(body,rotation());}public double gravity(){return body.profile().gravity();}
  public AABB ignored(){return body.ignoredCollisionRegion();}
  public void forces(long scene,long id){if(!body.nativeForcesEnabled())return;
   if(body.automaticAerodynamics()||!body.aerodynamicSurfaces().isEmpty()){
    double drag=Math.min(.10,body.profile().linearDrag()+velocity().length()*body.profile().speedDrag()+(body.damaged()?body.profile().damageDrag():0));
    owner().setDeltaMovement(velocity().scale(1-drag));body.setAngularVelocity(angular().scale(body.profile().angularDamping()));
    var air=SurfaceAerodynamics.forces(body);var motor=body.externalWrench();var wind=body.windVelocity().scale(20);NativeBridge.forces0(scene,id,new double[]{air.force().x,air.force().y,air.force().z,air.torque().x,air.torque().y,air.torque().z,motor.force().x,motor.force().y,motor.force().z,motor.torque().x,motor.torque().y,motor.torque().z,wind.x,wind.y,wind.z});return;
   }var result=body.automaticAerodynamics()?AutoAerodynamics.step(velocity(),angular(),body.forward(),body.up(),body.centerOfMass(),body.collisionBoxes(),mass(),body.damaged(),body.profile(),body.autoAeroProfile()):Aerodynamics.step(velocity(),angular(),body.forward(),body.up(),body.wingArea(),body.wingImbalance(),body.stabilizers(),body.damaged(),body.profile());
   Vec3 v=result.velocity().add(0,gravity(),0);double cap=body.profile().maximumSpeed();if(v.length()>cap)v=v.normalize().scale(cap);owner().setDeltaMovement(v);body.setAngularVelocity(result.angular());}
  public void accept(Vec3 p,Quaternionf q,Vec3 v,Vec3 w,boolean sleeping){body.applyNativePose(p,q);owner().setDeltaMovement(v);body.setAngularVelocity(w);owner().hasImpulse=true;}
  public void impact(int part,CompoundCollision.Contact c,double speed,double impulse){body.onNativeContact(part,c,speed,impulse);}
  public void completed(boolean sleeping){body.nativeStepCompleted(sleeping);}
  public double waterDensity(){return body.waterDensity();}
  public List<AABB> waterParts(){return body.collisionBoxes().stream().map(CompoundCollision::bounds).toList();}
 }
 private static final class LimbSource implements Source{
  final PlayerRagdollEntity entity;final int part;LimbSource(PlayerRagdollEntity e,int p){entity=e;part=p;}
  RigidBodyState body(){return entity.limbs().get(part);}public Entity owner(){return entity;}public int role(){return part;}public Vec3 position(){return body().position;}public Quaternionf rotation(){return new Quaternionf(body().orientation);}public Vec3 velocity(){return body().velocity;}public Vec3 angular(){return body().angular;}public double mass(){return 1/body().inverseMass;}public long revision(){return 0;}
  public AABB bounds(){return CompoundCollision.bounds(body().box());}
  public double[] geometry(){Vec3 h=body().half;return new double[]{0,0,0,0,0,0,1,h.x,h.y,h.z,.6,0};}
  public void accept(Vec3 p,Quaternionf q,Vec3 v,Vec3 w,boolean sleeping){var b=body();b.position=p;b.orientation.set(q);b.velocity=v;b.angular=w;}
  public double waterDensity(){return PhysicalistConfig.WATER_RAGDOLL_DENSITY.get();}
 }

 private static final class ChainSource implements Source{
  final PhysicalChainEntity chain;final int part;ChainSource(PhysicalChainEntity c,int p){chain=c;part=p;}
  boolean anchor(){return part>=chain.links().size();}int end(){return part-chain.links().size();}
  RigidBodyState body(){return chain.links().get(part);}public Entity owner(){return chain;}public int role(){return 200+part;}
  public Vec3 position(){return anchor()?chain.nativeAnchor(end()):body().position;}public Quaternionf rotation(){return anchor()?new Quaternionf():new Quaternionf(body().orientation);}
  // One tick of travel: the kinematic anchor reaches the attachment at the end of the step without a teleport.
  public Vec3 velocity(){return anchor()?chain.anchor(end()).subtract(chain.nativeAnchor(end())):body().velocity;}public Vec3 angular(){return anchor()?Vec3.ZERO:body().angular;}public double mass(){return anchor()?0:1/body().inverseMass;}public double gravity(){return anchor()?0:PhysicalistConfig.defaults().gravity();}public long revision(){return 0;}
  public AABB bounds(){return anchor()?new AABB(position(),position()).inflate(.01):CompoundCollision.bounds(body().box());}
  public double[] geometry(){if(anchor())return new double[0];Vec3 h=body().half;return new double[]{0,0,0,0,0,0,1,h.x,h.y,h.z,.5,0};}
  public double waterDensity(){return anchor()?Double.NaN:PhysicalistConfig.WATER_CHAIN_DENSITY.get();}
  public void accept(Vec3 p,Quaternionf q,Vec3 v,Vec3 w,boolean sleeping){if(anchor())chain.nativeAnchorMoved(end(),p);else{body().position=p;body().orientation.set(q);body().velocity=v;body().angular=w;}}
 }
 private final Map<UUID,PhysicalChainEntity> chains=new LinkedHashMap<>();private final Map<UUID,long[]> chainJoints=new HashMap<>();private final Map<UUID,long[]> chainEndJoints=new HashMap<>();private final Map<UUID,Long> chainLimits=new HashMap<>();
 void enqueue(PhysicalChainEntity chain){
  long[] old=chainJoints.get(chain.getUUID());if(old!=null&&old[0]!=chain.revision())settle();
  old=chainJoints.get(chain.getUUID());if(old!=null&&old[0]!=chain.revision()){
   var it=bindings.entrySet().iterator();while(it.hasNext()){var b=it.next().getValue();if(b.source.owner()==chain){NativeBridge.removeBody0(scene,b.id);it.remove();}}chainJoints.remove(chain.getUUID());
  }
  for(int end=0;end<2;end++){Entity e=chain.endpoint(end).entity(level);if(e instanceof PlayerRagdollEntity doll&&chain.endpoint(end).doll()){enqueue(doll);continue;}var body=e==null?null:PhysicalistBodies.find(e);if(body!=null&&chain.endpoint(end).data.getBoolean("Rigid"))enqueue(body);}
  for(int p=0;p<chain.links().size()+2;p++){var source=new ChainSource(chain,p);pending.put(key(source),source);}chains.put(chain.getUUID(),chain);PhysicalistBodies.register(chain.physicalistBody());
 }
 private void setupChains(){for(var chain:chains.values()){
  if(chain.isRemoved())continue;int n=chain.links().size();long[] ids=new long[n+3];ids[0]=chain.revision();boolean ready=true;
  for(int p=0;p<n+2;p++){var binding=bindings.get(chain.getUUID()+"/"+(200+p));if(binding==null){ready=false;break;}ids[p+1]=binding.id;}
  // Jointed ends wait for their body: a chain built on the kinematic anchor instead would never be rejoined.
  for(int end=0;end<2;end++)if(chain.endpoint(end).jointed()&&endBinding(chain,end)==null)ready=false;
  if(!ready||Arrays.equals(ids,chainJoints.get(chain.getUUID())))continue;
  long[] ends={-1,-1};
  for(int j=0;j<=n;j++){
   if(chain.cut(j))continue;long a=j==0?ids[n+1]:ids[j],b=j==n?ids[n+2]:ids[j+1];
   Vec3 la=j==0?Vec3.ZERO:new Vec3(0,chain.links().get(j-1).half.y,0),lb=j==n?Vec3.ZERO:new Vec3(0,-chain.links().get(j).half.y,0);
   if(j==0||j==n){int end=j==0?0:1;var binding=endBinding(chain,end);if(binding!=null){if(end==0){a=binding.id;la=chain.endpoint(end).local();}else{b=binding.id;lb=chain.endpoint(end).local();}}}
   // s[11]: joint friction (1/s); without it waves never leave the chain.
   long joint=NativeBridge.joint0(scene,a,b,new double[]{0,la.x,la.y,la.z,lb.x,lb.y,lb.z,-1,-1,-1,0,10,0,1,0,0,1,1});if(j==0)ends[0]=joint;else if(j==n)ends[1]=joint;
  }
  // The rope is slightly shorter than the links so it alone carries tension; equal lengths made both constraints fight and shake.
  if(chain.connected()&&(chain.endpoint(0).jointed()||chain.endpoint(1).jointed())){long a=ids[n+1],b=ids[n+2];Vec3 pa=Vec3.ZERO,pb=Vec3.ZERO;for(int end=0;end<2;end++){var binding=endBinding(chain,end);if(binding!=null){if(end==0){a=binding.id;pa=chain.endpoint(end).local();}else{b=binding.id;pb=chain.endpoint(end).local();}}}chainLimits.put(chain.getUUID(),NativeBridge.joint0(scene,a,b,new double[]{4,pa.x,pa.y,pa.z,pb.x,pb.y,pb.z,chain.length()*.985,0,0,0,0}));}else chainLimits.remove(chain.getUUID());
  chainJoints.put(chain.getUUID(),ids);chainEndJoints.put(chain.getUUID(),ends);
 }
  // Every chain end sits inside its holder (mob proxy, ragdoll limb, rigid body): contacts between them pushed the
  // holder away, the anchor followed, and chain and holder flew off together.
  List<Long> pairs=new ArrayList<>();for(var chain:chains.values()){if(chain.isRemoved())continue;long self=bits(chain);for(int end=0;end<2;end++){var p=chain.endpoint(end);if(p!=null&&p.data.hasUUID("Entity"))pairs.addAll(List.of(self,p.data.getUUID("Entity").getLeastSignificantBits()|1));}}
  long[] flat=pairs.stream().mapToLong(Long::longValue).toArray();if(!Arrays.equals(flat,apart)){NativeBridge.apart0(scene,flat);apart=flat;}
 }
 private long[] apart=new long[0];
 /** Native collision owner of an entity, as passed to createBody0. */
 static long bits(Entity e){return e.getUUID().getLeastSignificantBits()|1;}
 /** Native body holding a jointed (rigid or ragdoll) chain end, or null for anchored ends. */
 private Binding endBinding(PhysicalChainEntity chain,int end){var p=chain.endpoint(end);if(!p.jointed())return null;Entity owner=p.entity(level);return owner==null?null:bindings.get(owner.getUUID()+(p.doll()?"/1":"/-1"));}
 private static final class Binding{long id,revision=Long.MIN_VALUE,lastSeen,created;double mass;double[] geometry,last,sent;Source source;boolean paused,ignored;Vec3 before=Vec3.ZERO,beforeAngular=Vec3.ZERO,beforeCenter=Vec3.ZERO;Binding(Source source){this.source=source;}}
 private final ServerLevel level;public final long scene;private final TerrainCache terrain;private final ExternalLayer external;private final Map<String,Binding> bindings=new LinkedHashMap<>();private final Map<String,Source> pending=new LinkedHashMap<>();private final Map<UUID,long[]> rigs=new HashMap<>();private final Map<UUID,PlayerRagdollEntity> dolls=new LinkedHashMap<>();private long frame;private double lastMillis;private int sleeping;private int paused;
 NativeWorld(ServerLevel level){this.level=level;worker=worker(level);scene=NativeBridge.createScene0(9.81);terrain=new TerrainCache(level,scene);external=new ExternalLayer(scene);}
 private static String key(Source s){return s.owner().getUUID()+"/"+s.role();}
 void enqueue(PhysicsBody body){var s=new BodySource(body);pending.put(key(s),s);PhysicalistBodies.register(body);}
 void enqueue(PlayerRagdollEntity doll){if(!dolls.containsKey(doll.getUUID()))doll.relaxInitialSelfContacts();for(int p=0;p<6;p++){var s=new LimbSource(doll,p);pending.put(key(s),s);}dolls.put(doll.getUUID(),doll);PhysicalistBodies.register(doll.physicalistBody());}
 private static double[] state(Source s){var p=s.position();var q=s.rotation();var v=s.velocity().scale(20);var w=s.angular().scale(20);return new double[]{p.x,p.y,p.z,q.x,q.y,q.z,q.w,v.x,v.y,v.z,w.x,w.y,w.z,s.gravity()/.024525,0};}
 private static double[] alignedState(Source source,Binding binding){double[] s=state(source);if(binding.last!=null){double dot=0;for(int i=3;i<7;i++)dot+=s[i]*binding.last[i];if(dot<0)for(int i=3;i<7;i++)s[i]=-s[i];}return s;}
 /**
  * Entities keep orientation in a float Quaternionf, so reading back the native double pose shifts it by
  * ~1e-7. Compared at 1e-7 that rounding looked like a teleport every tick: the body (and its whole contact
  * island) was woken and a settled pile of 125 blocks never slept. Rotation uses float precision (1e-6,
  * about 1e-4 degrees); position stays at 1e-7.
  */
 private static boolean poseChanged(double[] s,double[] last){
  for(int a=0;a<3;a++)if(Math.abs(s[a]-last[a])>1e-7)return true;
  for(int a=3;a<7;a++)if(Math.abs(s[a]-last[a])>1e-6)return true;
  return false;
 }
 /**
  * A NaN pose or velocity from one entity used to fail the whole native step, and the dimension fell back to
  * Java physics for the rest of the session ("Rapier unavailable"). The body is put back to its last native state.
  */
 private double[] repaired(double[] s,Binding b){
  boolean finite=true;for(int i=0;i<14;i++)finite&=Double.isFinite(s[i]);if(finite)return s;
  if(warned.add(key(b.source)))org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Non-finite state from {} ({}), restored its last native pose",b.source.owner(),key(b.source));
  double[] last=b.last!=null&&Arrays.stream(b.last).allMatch(Double::isFinite)?b.last.clone():new double[]{b.source.owner().getX(),b.source.owner().getY(),b.source.owner().getZ(),0,0,0,1,0,0,0,0,0,0,b.source.gravity()/.024525,0};
  for(int i=0;i<14;i++)if(!Double.isFinite(last[i]))last[i]=i==6?1:0;last[14]=s[14];return last;
 }
 private final Set<String> warned=new HashSet<>();
 private static boolean same(double[] a,double[] b,double epsilon){if(a==null||a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(Math.abs(a[i]-b[i])>epsilon)return false;return true;}
 private void water(Source s,long id){
  if(!PhysicalistConfig.WATER_ENABLED.get())return;double density=s.waterDensity();if(Double.isNaN(density))return;
  var parts=s.waterParts();double[] spec=new double[3+parts.size()];boolean wet=false;
  for(int i=0;i<parts.size();i++){double h=PhysicalistWater.surface(level,parts.get(i));spec[3+i]=h;wet|=!Double.isNaN(h);}if(!wet)return;
  spec[0]=PhysicalistConfig.WATER_BUOYANCY.get()/PhysicalistWater.effective(density);spec[1]=PhysicalistConfig.WATER_LINEAR_DRAG.get();spec[2]=PhysicalistConfig.WATER_ANGULAR_DRAG.get();NativeBridge.water0(scene,id,spec);
 }
 /**
  * Rigid bodies always step on their own thread. At the end of a level tick the server gathers inputs and hands the
  * Rapier step to the worker; at the start of the next level tick (or before anything else touches the scene) it
  * picks the result up. The worker only runs the native step and never reads or writes Minecraft state.
  */
 private final java.util.concurrent.ExecutorService worker;private java.util.concurrent.Future<?> running;private List<Binding> stepping;private long started;private volatile double workerMillis;
 private static java.util.concurrent.ExecutorService worker(ServerLevel level){return java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Physicalist rigid bodies "+level.dimension().location());t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY);return t;});}
 /** Synchronous step on the calling thread: finishes any step in flight, then runs a whole one. Used by tests and benchmarks. */
 public void flush(){long timer=PhysicsMetrics.begin();try{settleStep();begin();if(stepping!=null){timedStep();}complete();}finally{PhysicsMetrics.end(timer);}}
 /** End of a level tick: gather inputs and start the next step on the physics thread. */
 public void launch(){long timer=PhysicsMetrics.begin();try{settleStep();begin();if(stepping!=null)running=worker.submit(this::timedStep);else complete();}finally{PhysicsMetrics.end(timer);}}
 /** Waits for the step in flight (if any) and applies its result. Safe to call any time on the server thread. */
 public void settle(){if(stepping==null)return;long timer=PhysicsMetrics.begin();try{settleStep();}finally{PhysicsMetrics.end(timer);}}
 private void settleStep(){if(stepping==null)return;var future=running;running=null;
  if(future!=null)try{future.get();}catch(InterruptedException e){Thread.currentThread().interrupt();stepping=null;throw new IllegalStateException("Interrupted while waiting for the physics thread",e);}catch(java.util.concurrent.ExecutionException e){stepping=null;throw e.getCause() instanceof RuntimeException r?r:new IllegalStateException(e.getCause());}
  complete();}
 private void timedStep(){long t=System.nanoTime();NativeBridge.step0(scene,.05);workerMillis=(System.nanoTime()-t)/1e6;}
 /** Gathers every queued body into the scene. Leaves {@link #stepping} set when there is something to step. */
 private void begin(){started=System.nanoTime();frame++;paused=0;List<Binding> live=new ArrayList<>();
  for(Source s:pending.values()){
   if(s.owner().isRemoved()||s.owner().level()!=level)continue;String key=key(s);Binding b=bindings.computeIfAbsent(key,k->new Binding(s));b.source=s;b.lastSeen=frame;
   long revision=s.revision();double mass=s.mass();if(b.id==0||revision<0||revision!=b.revision||mass!=b.mass){double[] geometry=s.geometry();if(b.id==0){double[] initial=repaired(state(s),b);b.id=NativeBridge.createBody0(scene,mass,s.gravity()/.024525,s.owner().getUUID().getLeastSignificantBits()|1,s.role(),initial,geometry);b.last=initial;b.created=frame;}else if(!same(geometry,b.geometry,1e-4)||mass!=b.mass)NativeBridge.geometry0(scene,b.id,mass,s.owner().getUUID().getLeastSignificantBits()|1,s.role(),geometry);b.geometry=geometry;b.revision=revision;b.mass=mass;}
   AABB excluded=s.ignored();if(excluded!=null){NativeBridge.ignore0(scene,b.id,new double[]{excluded.minX,excluded.minY,excluded.minZ,excluded.maxX,excluded.maxY,excluded.maxZ});b.ignored=true;}else if(b.ignored){NativeBridge.ignore0(scene,b.id,new double[0]);b.ignored=false;}
   AABB region=s.bounds().expandTowards(s.velocity()).inflate(.02);boolean ready=terrain.ensure(region,frame);if(ready)terrain.ensure(region.inflate(1),frame);b.paused=!ready;if(!ready)paused++;
   double[] current=alignedState(s,b);boolean externalChange=poseChanged(current,b.last);for(int a=7;a<13&&!externalChange;a++)externalChange=Math.abs(current[a]-b.last[a])>1e-7;
   if(ready&&(b.last[14]==0||externalChange))s.forces(scene,b.id);if(ready)water(s,b.id);b.before=s.velocity();b.beforeAngular=s.angular();b.beforeCenter=s.center();live.add(b);
  }
  pending.clear();
  var it=bindings.entrySet().iterator();while(it.hasNext()){var e=it.next();Binding b=e.getValue();if(b.source.owner().isRemoved()||frame-b.lastSeen>100){NativeBridge.removeBody0(scene,b.id);it.remove();}else if(b.lastSeen==frame-1){double[] hold=repaired(state(b.source),b);hold[14]=8;NativeBridge.sync0(scene,new long[]{b.id},hold);}}
  Map<Entity,AABB> regions=new LinkedHashMap<>();for(var b:live)if(!b.paused)regions.merge(b.source.owner(),b.source.bounds().expandTowards(b.source.velocity()),AABB::minmax);external.update(level,regions);
  setupRigs();setupChains();long[] ids=new long[live.size()];double[] input=new double[live.size()*15];
  for(int i=0;i<live.size();i++){Binding b=live.get(i);ids[i]=b.id;double[] s=repaired(alignedState(b.source,b),b);int flags=b.paused?8:4;if(b.source instanceof LimbSource&&b.created==frame)flags|=2;if(poseChanged(s,b.last))flags|=1;for(int a=7;a<13;a++)if(Math.abs(s[a]-b.last[a])>1e-7){flags|=2;break;}s[14]=flags;System.arraycopy(s,0,input,i*15,15);b.sent=s;}
  if(!live.isEmpty())NativeBridge.sync0(scene,ids,input);stepping=live;gatherNanos=System.nanoTime()-started;
 }
 private long gatherNanos;
 /**
  * A command, packet or other mod moved or pushed the entity while its step was on the physics thread. Its change
  * wins: the step result is not written over it, and the next step picks it up as an external change.
  */
 private static boolean movedMeanwhile(Binding b){if(!(b.source instanceof BodySource)||b.sent==null)return false;double[] now=state(b.source);if(poseChanged(now,b.sent)){double dot=0;for(int i=3;i<7;i++)dot+=now[i]*b.sent[i];if(dot<0)for(int i=3;i<7;i++)now[i]=-now[i];if(poseChanged(now,b.sent))return true;}for(int a=7;a<13;a++)if(Math.abs(now[a]-b.sent[a])>1e-7)return true;return false;}
 /** Reads the finished step back into the entities and runs the per-tick bookkeeping. */
 private void complete(){List<Binding> live=stepping==null?List.of():stepping;stepping=null;long applyStarted=System.nanoTime();
  if(!live.isEmpty()){long[] ids=new long[live.size()];for(int i=0;i<ids.length;i++)ids[i]=live.get(i).id;double[] output=NativeBridge.read0(scene,ids);sleeping=0;
   for(int i=0;i<live.size();i++){Binding b=live.get(i);b.last=Arrays.copyOfRange(output,i*15,(i+1)*15);var s=b.last;boolean rest=s[14]!=0;if(rest)sleeping++;if(b.source.owner().isRemoved()||movedMeanwhile(b))continue;b.source.accept(new Vec3(s[0],s[1],s[2]),new Quaternionf((float)s[3],(float)s[4],(float)s[5],(float)s[6]),new Vec3(s[7],s[8],s[9]).scale(.05),new Vec3(s[10],s[11],s[12]).scale(.05),rest);}
   Map<Long,Binding> byId=new HashMap<>();for(var b:live)byId.put(b.id,b);
   for(var b:live){double[] contacts=NativeBridge.contacts0(scene,b.id);for(int at=0;at+9<contacts.length&&!b.source.owner().isRemoved();at+=10){Binding other=byId.get((long)contacts[at+1]);Vec3 point=new Vec3(contacts[at+5],contacts[at+6],contacts[at+7]);Vec3 relative=b.before.add(b.beforeAngular.cross(point.subtract(b.beforeCenter))).subtract(other==null?external.velocity((long)contacts[at+1]):other.before.add(other.beforeAngular.cross(point.subtract(other.beforeCenter)))),normal=new Vec3(contacts[at+2],contacts[at+3],contacts[at+4]);double speed=Math.max(0,-relative.dot(normal));external.reaction((long)contacts[at+1],new Vec3(contacts[at+5],contacts[at+6],contacts[at+7]),normal.scale(contacts[at+9]*.05));b.source.impact((int)contacts[at],new CompoundCollision.Contact(normal,new Vec3(contacts[at+5],contacts[at+6],contacts[at+7]),contacts[at+8]),speed,contacts[at+9]*.05);}}
  }
  for(var b:live)if(!b.source.owner().isRemoved())b.source.completed(b.last[14]!=0);
  for(var doll:dolls.values()){if(doll.isRemoved())continue;boolean all=true;for(int p=0;p<6;p++){Binding b=bindings.get(doll.getUUID()+"/"+p);if(b==null||b.last[14]==0){all=false;break;}}doll.nativeStepFinished(all);}
  for(var chain:chains.values())if(!chain.isRemoved()){
   chain.nativeStepFinished();Long limit=chainLimits.get(chain.getUUID());if(limit!=null)for(int end=0;end<2;end++)if(chain.endpoint(end).data.hasUUID("Ship")){double[] f=NativeBridge.jointForce0(scene,limit);chain.endpoint(end).applyForce(level,new Vec3(f[0],f[1],f[2]).scale(end==0?-1:1),chain.anchor(end));}var ends=chainEndJoints.get(chain.getUUID());if(ends!=null)for(int end=0;end<2;end++)if(ends[end]>=0&&chain.endpoint(end).data.hasUUID("Ship")){double[] f=NativeBridge.jointForce0(scene,ends[end]);chain.endpoint(end).applyForce(level,new Vec3(f[0],f[1],f[2]).scale(end==0?-1:1),chain.anchor(end));}
  }
  chains.entrySet().removeIf(e->e.getValue().isRemoved());chainJoints.keySet().retainAll(chains.keySet());chainEndJoints.keySet().retainAll(chains.keySet());chainLimits.keySet().retainAll(chains.keySet());
  dolls.entrySet().removeIf(e->e.getValue().isRemoved());rigs.keySet().retainAll(dolls.keySet());terrain.prune(frame);lastMillis=(gatherNanos+System.nanoTime()-applyStarted)/1e6;
 }
 private void setupRigs(){for(var e:dolls.entrySet()){long[] ids=new long[6];boolean ready=true;for(int i=0;i<6;i++){Binding b=bindings.get(e.getKey()+"/"+i);if(b==null){ready=false;break;}ids[i]=b.id;}if(!ready||Arrays.equals(ids,rigs.get(e.getKey())))continue;
  // s[7]: twist about the limb's own axis, s[9]: swing cone. Twist equal to the cone let legs spin 95 degrees.
  var rig=e.getValue().rig();for(int i=0;i<6;i++){if(i==1)continue;Vec3 a=rig.torsoAnchor()[i],b=rig.limbAnchor()[i];NativeBridge.joint0(scene,ids[1],ids[i],new double[]{0,a.x,a.y,a.z,b.x,b.y,b.z,rig.twist()[i],0,rig.swing()[i],0,0,0,1,0,1});}
  rigs.put(e.getKey(),ids);
 }}
 public boolean simulates(Entity entity){for(var b:bindings.values())if(b.source.owner()==entity&&b.id!=0&&!b.paused&&b.lastSeen==frame)return true;return false;}
 public String bodyReport(Entity entity){for(var b:bindings.values())if(b.source.owner()==entity)return "Rapier3D body="+b.id+" state="+(b.paused?"waiting for terrain":b.lastSeen!=frame?"awaiting entity tick":b.last[14]!=0?"sleeping":"simulating")+" forces="+(b.source instanceof BodySource body&&!body.body.nativeForcesEnabled()?"flight controller":"Physicalist")+" frame="+b.lastSeen+"/"+frame;for(var s:pending.values())if(s.owner()==entity)return "Queued for Rapier3D";return "Not registered in native scene";}
 public void invalidate(net.minecraft.core.BlockPos pos){terrain.invalidate(pos);}public String report(){return String.format(java.util.Locale.ROOT,"%s: %d bodies, %d sleeping, %d paused, %d terrain sections (%d bakes), %.3f ms on the server thread, %.3f ms on the physics thread",level.dimension().location(),bindings.size(),sleeping,paused,terrain.resident(),terrain.baked(),lastMillis,workerMillis);}
 @Override public void close(){var future=running;running=null;stepping=null;if(future!=null)try{future.get(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ignored){}worker.shutdownNow();NativeBridge.destroyScene0(scene);bindings.clear();pending.clear();rigs.clear();dolls.clear();chains.clear();chainJoints.clear();chainEndJoints.clear();chainLimits.clear();}
}
