package dev.physicalist.nativecore;
import dev.physicalist.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
/** Registration and lifetime of dimension scenes. No Minecraft access from native threads. */
public final class NativeWorlds {
 private static String failure;
 private static final Map<ServerLevel,NativeWorld> WORLDS=new java.util.concurrent.ConcurrentHashMap<>();
 public static boolean enabled(){return failure==null&&NativeBridge.AVAILABLE&&PhysicalistConfig.NATIVE_ENABLED.get();}
 public static NativeWorld of(ServerLevel level){if(!enabled())throw new IllegalStateException(NativeBridge.STATUS);return WORLDS.computeIfAbsent(level,NativeWorld::new);}
 public static boolean enqueue(PhysicsBody body){if(!enabled()||!(body.entity().level() instanceof ServerLevel level))return false;of(level).enqueue(body);return true;}
 public static boolean enqueue(PlayerRagdollEntity body){if(!enabled()||!(body.level() instanceof ServerLevel level))return false;of(level).enqueue(body);return true;}
 public static boolean enqueue(PhysicalChainEntity chain){if(!enabled()||!(chain.level() instanceof ServerLevel level))return false;of(level).enqueue(chain);return true;}
 private static int failures;private static long lastFailure;
 /**
  * A failed step only rebuilds that dimension's scene: bodies, ragdolls and chains enqueue themselves again next
  * tick. Native physics is switched off for the session only when the rebuilt scene keeps failing.
  */
 public static void flush(ServerLevel level){var scene=WORLDS.get(level);if(scene!=null)try{scene.flush();}catch(RuntimeException error){
  long now=System.currentTimeMillis();failures=now-lastFailure<30_000?failures+1:1;lastFailure=now;var log=org.slf4j.LoggerFactory.getLogger("Physicalist");
  if(failures>=5){failure=error.toString();log.error("Native simulation keeps failing; resuming Java physics",error);closeAll();}else{log.error("Native step failed; rebuilding the scene of "+level.dimension().location(),error);close(level);}
 }}
 public static void invalidate(ServerLevel level,BlockPos pos){var scene=WORLDS.get(level);if(scene!=null)scene.invalidate(pos);}
 public static void close(ServerLevel level){var scene=WORLDS.remove(level);if(scene!=null)scene.close();}
 public static void closeAll(){for(var world:WORLDS.values())world.close();WORLDS.clear();}
 public static boolean simulates(net.minecraft.world.entity.Entity entity){var scene=WORLDS.get(entity.level());return enabled()&&scene!=null&&scene.simulates(entity);}
 public static String bodyReport(net.minecraft.world.entity.Entity entity){if(!enabled())return failure==null?"Java physics: native backend disabled or unavailable":"Java fallback: "+failure;var scene=WORLDS.get(entity.level());return scene==null?"Not registered in native scene":scene.bodyReport(entity);}
 public static List<String> report(){List<String> out=new ArrayList<>();out.add(failure==null?NativeBridge.STATUS:"Java fallback after native error: "+failure);for(var world:WORLDS.values())out.add(world.report());return out;}
 private NativeWorlds(){}
}
