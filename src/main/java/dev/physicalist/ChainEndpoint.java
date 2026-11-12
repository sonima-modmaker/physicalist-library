package dev.physicalist;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.util.UUID;
/** Persistent local attachment. Sable is optional; Aeronautics uses its sublevels. */
public final class ChainEndpoint {
 public final CompoundTag data;
 public ChainEndpoint(CompoundTag data){this.data=data.copy();}
 public static ChainEndpoint world(Vec3 p){var t=new CompoundTag();put(t,p);return new ChainEndpoint(t);}
 public static ChainEndpoint entity(Entity e,Vec3 hit){
  var result=world(hit);var body=PhysicalistBodies.find(e);boolean rigid=body!=null&&!(e instanceof PhysicalChainEntity)&&!(e instanceof PlayerRagdollEntity);Vec3 offset;
  if(rigid){var q=body.nativeOrientation().conjugate();var v=q.transform(new Vector3f((float)(hit.x-e.getX()),(float)(hit.y-e.getY()),(float)(hit.z-e.getZ())));offset=new Vec3(v.x,v.y,v.z);}
  else if(e instanceof PlayerRagdollEntity doll){int part=nearestLimb(doll,hit);offset=dollLocal(doll,part,hit);result.data.putBoolean("Doll",true);result.data.putInt("Part",part);}
  else if(e instanceof net.minecraft.world.entity.LivingEntity)offset=new Vec3(0,e.getBbHeight()*.55,0);
  else if(e instanceof PhysicalChainEntity)offset=Vec3.ZERO;
  else{Vec3 d=hit.subtract(e.position());var v=new org.joml.Quaternionf().rotateY((float)Math.toRadians(e.getYRot())).transform(new Vector3f((float)d.x,(float)d.y,(float)d.z));offset=new Vec3(v.x,v.y,v.z);}
  put(result.data,offset);result.data.putUUID("Entity",e.getUUID());result.data.putBoolean("Rigid",rigid);return result;
 }
 /** The limb (head, torso, arm, leg) whose box is nearest to the hit point: a chain hooks where it was attached. */
 private static int nearestLimb(PlayerRagdollEntity doll,Vec3 hit){int best=1;double nearest=Double.MAX_VALUE;
  for(int i=0;i<doll.limbs().size();i++){Vec3 l=dollLocal(doll,i,hit);var limb=doll.limbs().get(i);var v=new org.joml.Quaternionf(limb.orientation).transform(new Vector3f((float)l.x,(float)l.y,(float)l.z));double d=limb.position.add(v.x,v.y,v.z).distanceToSqr(hit);if(d<nearest-1e-6){nearest=d;best=i;}}
  return best;}
 /** Hit point in a limb's frame, kept inside its box. */
 private static Vec3 dollLocal(PlayerRagdollEntity doll,int part,Vec3 hit){var limb=doll.limbs().get(part);var v=new org.joml.Quaternionf(limb.orientation).conjugate().transform(new Vector3f((float)(hit.x-limb.position.x),(float)(hit.y-limb.position.y),(float)(hit.z-limb.position.z)));Vec3 h=limb.half;return new Vec3(Math.clamp(v.x,-h.x,h.x),Math.clamp(v.y,-h.y,h.y),Math.clamp(v.z,-h.z,h.z));}
 /** Ragdoll limb a ragdoll end hangs on (1, the torso, for chains saved before 0.18.1). */
 public int part(){return data.contains("Part")?Math.clamp(data.getInt("Part"),0,5):1;}
 public static ChainEndpoint block(ServerLevel level,Vec3 local){var result=world(local);UUID ship=ChainSableBridge.capture(level,local);if(ship!=null)result.data.putUUID("Ship",ship);return result;}
 public Vec3 local(){return new Vec3(data.getDouble("X"),data.getDouble("Y"),data.getDouble("Z"));}
 public Entity entity(ServerLevel level){return data.hasUUID("Entity")?level.getEntity(data.getUUID("Entity")):null;}
 public Vec3 resolve(ServerLevel level){
  if(data.hasUUID("Entity")){Entity e=entity(level);if(e==null||!e.isAlive())return null;var p=local();org.joml.Quaternionf q;
   if(e instanceof PlayerRagdollEntity doll){
    // Chains saved before 0.14 held a ragdoll as a loose entity at its torso centre.
    if(!doll()){data.putBoolean("Doll",true);put(data,Vec3.ZERO);p=Vec3.ZERO;}
    var limb=doll.limbs().get(part());var v=new org.joml.Quaternionf(limb.orientation).transform(new Vector3f((float)p.x,(float)p.y,(float)p.z));return limb.position.add(v.x,v.y,v.z);}
   if(data.getBoolean("Rigid")){var body=PhysicalistBodies.find(e);if(body==null)return null;q=body.nativeOrientation();}else q=new org.joml.Quaternionf().rotateY((float)-Math.toRadians(e.getYRot()));
   var v=q.transform(new Vector3f((float)p.x,(float)p.y,(float)p.z));return e.position().add(v.x,v.y,v.z);}

  if(data.hasUUID("Ship"))return ChainSableBridge.resolve(level,data.getUUID("Ship"),local());
  return local();
 }
 public boolean externalReaction(){return data.hasUUID("Ship")||loose();}
 /** A ragdoll end: jointed to one of its limbs ({@link #part}) inside the native scene. */
 public boolean doll(){return data.getBoolean("Doll");}
 /** A rigid body or ragdoll end, held by native joints rather than by the tether. */
 public boolean jointed(){return data.hasUUID("Entity")&&(data.getBoolean("Rigid")||doll());}
 /** A non-rigid entity (player, mob): held by the chain's tether, not by solver forces. */
 public boolean loose(){return data.hasUUID("Entity")&&!data.getBoolean("Rigid")&&!doll();}
 /** Mass in kg used to share the tether between both ends; infinite for world and ship ends. */
 static double mass(Entity e){
  if(e==null)return Double.POSITIVE_INFINITY;if(e instanceof net.minecraft.world.entity.player.Player)return 80;
  if(e instanceof PhysicalistBodyProvider provider&&provider.physicalistBody()!=null)return Math.max(1,provider.physicalistBody().aerodynamicMass());
  return Math.clamp(e.getBbWidth()*e.getBbWidth()*e.getBbHeight()*120,5,2000);
 }
 /** Client-side attachment point for this frame, or null for world and ship ends. */
 public Entity findNear(Level level,net.minecraft.world.phys.AABB near){
  if(!data.hasUUID("Entity"))return null;UUID id=data.getUUID("Entity");var found=level.getEntities((Entity)null,near.inflate(6),c->c.getUUID().equals(id));return found.isEmpty()?null:found.getFirst();
 }
 public Vec3 resolveClient(Level level,Entity e,float partial){
  if(data.hasUUID("Ship"))return ChainSableBridge.resolveClient(level,data.getUUID("Ship"),local(),partial);
  if(!data.hasUUID("Entity"))return local();
  if(e==null||e.isRemoved())return null;var p=local();org.joml.Quaternionf q;
  if(e instanceof PlayerRagdollEntity doll){if(!doll())return doll.renderPosition(1,partial);int part=part();var v=doll.renderRotation(part,partial).transform(new Vector3f((float)p.x,(float)p.y,(float)p.z));return doll.renderPosition(part,partial).add(v.x,v.y,v.z);}
  if(data.getBoolean("Rigid")){if(e instanceof PhysicalBlockEntity block)q=block.visualOrientation(partial);else if(e instanceof PhysicalistBodyProvider provider&&provider.physicalistBody()!=null)q=provider.physicalistBody().nativeOrientation();else return null;}
  else q=new org.joml.Quaternionf().rotateY((float)-Math.toRadians(net.minecraft.util.Mth.rotLerp(partial,e.yRotO,e.getYRot())));
  var v=q.transform(new Vector3f((float)p.x,(float)p.y,(float)p.z));return e.getPosition(partial).add(v.x,v.y,v.z);
 }
 public void applyForce(ServerLevel level,Vec3 force,Vec3 at){
  if(data.hasUUID("Ship")){if(force.lengthSqr()>1e-12)ChainSableBridge.force(level,data.getUUID("Ship"),at,force);return;}
  if(!data.hasUUID("Entity")||data.getBoolean("Rigid"))return;Entity entity=entity(level);if(entity==null||!entity.isAlive())return;
  var body=PhysicalistBodies.find(entity);double mass=body!=null?Math.max(1,body.aerodynamicMass()):entity instanceof net.minecraft.world.entity.player.Player?80:Math.clamp(entity.getBbWidth()*entity.getBbWidth()*entity.getBbHeight()*120,5,2000);
  Vec3 change=force.scale(1/(mass*400));double cap=entity instanceof net.minecraft.world.entity.player.Player?.18:.35;if(change.length()>cap)change=change.normalize().scale(cap);
  entity.setDeltaMovement(entity.getDeltaMovement().add(change));entity.hasImpulse=true;entity.hurtMarked=true;
 }
 private static void put(CompoundTag t,Vec3 p){if(!RigidBodyState.finite(p))throw new IllegalArgumentException("Invalid anchor");t.putDouble("X",p.x);t.putDouble("Y",p.y);t.putDouble("Z",p.z);}
}
