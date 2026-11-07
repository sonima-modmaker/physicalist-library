package dev.physicalist.nativecore;
import dev.physicalist.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import org.joml.Quaternionf;
import org.joml.Matrix3f;
import org.joml.Vector3f;
/** Kinematic contact proxies for external moving worlds and ordinary entities, with reaction impulses. */
final class ExternalLayer {
 private record Proxy(long id,double[] geometry,double[] state,int role,long owner){}
 private final long scene;private final List<Proxy> proxies=new ArrayList<>();private final Map<Long,PhysicalistExternalCollisions.Collider> active=new HashMap<>();
 ExternalLayer(long scene){this.scene=scene;}
 void update(ServerLevel level,Map<Entity,AABB> queries){SableSupport.install();List<PhysicalistExternalCollisions.Collider> all=new ArrayList<>();Set<String> seen=new HashSet<>();Set<Entity> owners=queries.keySet();Map<PhysicalistExternalCollisions.Collider,Long> playerShapes=new IdentityHashMap<>();
  // Entity proxies carry their entity as owner so a chain can skip the mob it is attached to.
  Map<PhysicalistExternalCollisions.Collider,Long> entityOwners=new IdentityHashMap<>();
  for(var entry:queries.entrySet()){
   for(var collider:PhysicalistExternalCollisions.gather(level,entry.getKey(),entry.getValue().inflate(.25))){String key=collider.box().center()+"/"+Arrays.toString(collider.box().half())+"/"+Arrays.toString(collider.box().axes());if(seen.add(key))all.add(collider);}
   for(var entity:level.getEntities(entry.getKey(),entry.getValue().inflate(.25),e->e.isAlive()&&!e.isSpectator()&&!owners.contains(e)&&(!(e instanceof net.minecraft.world.entity.player.Player)||entry.getKey() instanceof PhysicalChainEntity)&&(e.getBbWidth()>0&&e.getBbHeight()>0))){
    if(entity instanceof net.minecraft.world.entity.player.Player && entry.getKey() instanceof PhysicalChainEntity chain && chain.attachedTo(entity.getUUID()))continue;
    PhysicsBody body=PhysicalistBodies.find(entity);List<CompoundCollision.Box> boxes=body==null?List.of(CompoundCollision.box(entity.getBoundingBox())):body.collisionBoxes();int i=0;for(var box:boxes){if(!seen.add(entity.getUUID()+"/"+(i++)+ (entity instanceof net.minecraft.world.entity.player.Player?"/"+entry.getKey().getUUID():"")))continue;
     var collider=new PhysicalistExternalCollisions.Collider(box,entity.getDeltaMovement(),(point,impulse)->{if(entity.isRemoved())return;double mass=body==null?Math.clamp(entity.getBoundingBox().getXsize()*entity.getBoundingBox().getYsize()*entity.getBoundingBox().getZsize()*120,5,2000):Math.max(.1,body.aerodynamicMass());if(body!=null)BodyForces.impulse(body,impulse.scale(-1),point);else {Vec3 change=impulse.scale(-1/mass);double cap=entity instanceof net.minecraft.world.entity.player.Player?.18:.5;if(change.length()>cap)change=change.normalize().scale(cap);entity.setDeltaMovement(entity.getDeltaMovement().add(change));}entity.hasImpulse=true;if(entity instanceof net.minecraft.world.entity.player.Player)entity.hurtMarked=true;});all.add(collider);if(entity instanceof net.minecraft.world.entity.player.Player)playerShapes.put(collider,entry.getKey().getUUID().getLeastSignificantBits()|1);else entityOwners.put(collider,NativeWorld.bits(entity));}
   }
  }
  active.clear();long[] ids=new long[all.size()];double[] states=new double[all.size()*15];
  for(int i=0;i<all.size();i++){var c=all.get(i);int role=playerShapes.containsKey(c)?10000:-1;long owner=playerShapes.getOrDefault(c,entityOwners.getOrDefault(c,0L));var box=c.box();var axes=box.axes();Quaternionf q=new Quaternionf().setFromNormalized(new Matrix3f().setColumn(0,v(axes[0])).setColumn(1,v(axes[1])).setColumn(2,v(axes[2]))).normalize();Vec3 p=box.center(),speed=c.velocity().scale(20);double[] state={p.x,p.y,p.z,q.x,q.y,q.z,q.w,speed.x,speed.y,speed.z,0,0,0,0,4};double[] shape={0,0,0,0,0,0,1,box.half()[0],box.half()[1],box.half()[2],.6,0};Proxy proxy=i<proxies.size()?proxies.get(i):null;
   if(proxy==null){long id=NativeBridge.createBody0(scene,0,0,owner,role,state,shape);proxy=new Proxy(id,shape,state.clone(),role,owner);proxies.add(proxy);}else if(owner!=proxy.owner()||role!=proxy.role()||!Arrays.equals(shape,proxy.geometry()))NativeBridge.geometry0(scene,proxy.id(),0,owner,role,shape);
   for(int a=0;a<13;a++)if(java.lang.Math.abs(state[a]-proxy.state()[a])>1e-8){state[14]=7;break;}
   ids[i]=proxy.id();System.arraycopy(state,0,states,i*15,15);active.put(proxy.id(),c);proxies.set(i,new Proxy(proxy.id(),shape,state.clone(),role,owner));
  }
  while(proxies.size()>all.size())NativeBridge.removeBody0(scene,proxies.remove(proxies.size()-1).id());if(ids.length>0)NativeBridge.sync0(scene,ids,states);
 }
 private static Vector3f v(Vec3 p){return new Vector3f((float)p.x,(float)p.y,(float)p.z);}
 Vec3 velocity(long id){var c=active.get(id);return c==null?Vec3.ZERO:c.velocity();}
 void reaction(long id,Vec3 point,Vec3 impulse){var c=active.get(id);if(c!=null&&c.reaction()!=null&&impulse.lengthSqr()>1e-12)c.reaction().accept(point,impulse);}
}
