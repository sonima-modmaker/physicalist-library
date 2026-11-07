package dev.physicalist.nativecore;
import dev.physicalist.*;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import net.minecraft.core.BlockPos;
import org.joml.Vector3dc;
/** Optional Sable bridge. Reflection keeps the core independent of any particular Sable artifact. */
public final class SableSupport {
 /** A mod with a compiled Sable adapter can supply that bridge without reflective API probing. */
 public static synchronized void useExternalBridge(){attempted=true;}
 private static boolean attempted,failed;private static Class<?> containerClass,forceClass;private static Object forceGroup;private static final Map<String,Method> METHODS=new java.util.concurrent.ConcurrentHashMap<>();
 public static synchronized void install(){if(attempted)return;attempted=true;try{containerClass=Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");forceClass=Class.forName("dev.ryanhcode.sable.api.physics.force.ForceGroup");forceGroup=forceClass.getConstructor(net.minecraft.network.chat.Component.class,net.minecraft.network.chat.Component.class,int.class,boolean.class).newInstance(net.minecraft.network.chat.Component.literal("Physicalist"),net.minecraft.network.chat.Component.literal("Native body contacts"),0x70e6a1,false);PhysicalistExternalCollisions.register(SableSupport::gather);org.slf4j.LoggerFactory.getLogger("Physicalist").info("Physicalist Sable moving-world bridge installed");}catch(ClassNotFoundException absent){}catch(ReflectiveOperationException e){warn(e);}}
 private static Object call(Object object,String name,Class<?>[] types,Object... args)throws ReflectiveOperationException{String key=object.getClass().getName()+"#"+name+Arrays.toString(types);Method m=METHODS.get(key);if(m==null){m=object.getClass().getMethod(name,types);METHODS.put(key,m);}return m.invoke(object,args);}
 private static Object call(Object o,String name)throws ReflectiveOperationException{return call(o,name,new Class<?>[0]);}
 private static Vec3 transform(Object pose,String name,Vec3 p)throws ReflectiveOperationException{return (Vec3)call(pose,name,new Class<?>[]{Vec3.class},p);}
 private static List<PhysicalistExternalCollisions.Collider> gather(ServerLevel level,Entity body,AABB query){List<PhysicalistExternalCollisions.Collider> result=new ArrayList<>();if(failed)return result;try{
  Object container=containerClass.getDeclaredMethod("getContainer",ServerLevel.class).invoke(null,level);if(container==null)return result;
  for(Object ship:(Iterable<?>)call(container,"getAllSubLevels")){
   if((boolean)call(ship,"isRemoved"))continue;AABB bounds=(AABB)call(call(ship,"boundingBox"),"toMojang");if(!bounds.inflate(.1).intersects(query))continue;
   Object pose=call(ship,"logicalPose");Vec3 first=transform(pose,"transformPositionInverse",new Vec3(query.minX,query.minY,query.minZ));AABB local=new AABB(first,first);
   for(int i=0;i<8;i++){Vec3 p=transform(pose,"transformPositionInverse",new Vec3((i&1)==0?query.minX:query.maxX,(i&2)==0?query.minY:query.maxY,(i&4)==0?query.minZ:query.maxZ));local=local.minmax(new AABB(p,p));}
   if(local.getXsize()*local.getYsize()*local.getZsize()>32768)continue;
   Vec3[] axes={transform(pose,"transformNormal",new Vec3(1,0,0)),transform(pose,"transformNormal",new Vec3(0,1,0)),transform(pose,"transformNormal",new Vec3(0,0,1))};
   Vector3dc lv=(Vector3dc)ship.getClass().getField("latestLinearVelocity").get(ship),av=(Vector3dc)ship.getClass().getField("latestAngularVelocity").get(ship);Vec3 linear=lv==null?Vec3.ZERO:new Vec3(lv.x(),lv.y(),lv.z()).scale(.05),omega=av==null?Vec3.ZERO:new Vec3(av.x(),av.y(),av.z()).scale(.05);
   Object plot=call(ship,"getPlot");Vec3 pivot=transform(pose,"transformPosition",Vec3.atCenterOf((BlockPos)call(plot,"getCenterBlock")));
   for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(local.minX,local.minY,local.minZ),BlockPos.containing(local.maxX,local.maxY,local.maxZ))){var state=level.getBlockState(pos);if(state.isAir())continue;
    for(var raw:state.getCollisionShape(level,pos).toAabbs()){AABB b=raw.move(pos);Vec3 center=transform(pose,"transformPosition",b.getCenter());var box=new CompoundCollision.Box(center,axes,new double[]{b.getXsize()/2,b.getYsize()/2,b.getZsize()/2});
     result.add(new PhysicalistExternalCollisions.Collider(box,linear.add(omega.cross(center.subtract(pivot))),(point,impulse)->{try{Vec3 localPoint=transform(pose,"transformPositionInverse",point),force=transform(pose,"transformNormalInverse",impulse.scale(-400));Object group=call(ship,"getOrCreateQueuedForceGroup",new Class<?>[]{forceClass},forceGroup);call(group,"applyAndRecordPointForce",new Class<?>[]{Vector3dc.class,Vector3dc.class},new org.joml.Vector3d(localPoint.x,localPoint.y,localPoint.z),new org.joml.Vector3d(force.x,force.y,force.z));}catch(ReflectiveOperationException ex){warn(ex);}}));
    }
   }
  }
 }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){warn(ex);}return result;}
 private static synchronized void warn(Throwable e){if(!failed){failed=true;org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Sable bridge unavailable for this API version",e);}}
 private SableSupport(){}
}
