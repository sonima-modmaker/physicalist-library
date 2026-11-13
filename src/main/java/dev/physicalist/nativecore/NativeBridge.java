package dev.physicalist.nativecore;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
/** Independently implemented JNI ABI. No MCS code or binaries are used. */
public final class NativeBridge {
 public static final boolean AVAILABLE;public static final String STATUS;
 static{boolean available=false;String status;
  try{if(Boolean.getBoolean("physicalist.native.disable"))throw new IllegalStateException("disabled by JVM property");
   String os=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT),arch=System.getProperty("os.arch");String target=(os.contains("win")?"windows":os.contains("mac")?"macos":"linux")+"-"+(arch.equals("aarch64")||arch.equals("arm64")?"aarch64":"x86_64");String name=os.contains("win")?"physicalist_native.dll":os.contains("mac")?"libphysicalist_native.dylib":"libphysicalist_native.so";
   String build="";
   if(!Boolean.getBoolean("physicalist.native.baseline")&&NativeBridge.class.getResource("/natives/"+target+"/"+variant(name,"_v3"))!=null){
    try{System.load(extract(target,variant(name,"").replace("physicalist_native","physicalist_cpu")).toString());if(NativeCpu.level0()>=3){name=variant(name,"_v3");build=" / x86-64-v3";}}
    catch(Exception|LinkageError ex){org.slf4j.LoggerFactory.getLogger("Physicalist").info("CPU probe unavailable, using the baseline backend: {}",ex.toString());}
   }
   System.load(extract(target,name).toString());if(abi0()!=6)throw new IllegalStateException("native ABI mismatch");available=true;status="Rapier3D 0.36.1 / f64 / ABI 6"+build;
  }catch(Exception|LinkageError ex){status="Java fallback: "+ex.getMessage();}
  AVAILABLE=available;STATUS=status;org.slf4j.LoggerFactory.getLogger("Physicalist").info("Physicalist backend: {}",STATUS);
 }
 /** physicalist_native.dll + "_v3" -> physicalist_native_v3.dll */
 private static String variant(String name,String suffix){int dot=name.lastIndexOf('.');return name.substring(0,dot)+suffix+name.substring(dot);}
 /** Copies a packaged library to a content-addressed temp folder and returns its path. */
 private static Path extract(String target,String name)throws Exception{
  byte[] bytes;try(var input=NativeBridge.class.getResourceAsStream("/natives/"+target+"/"+name)){if(input==null)throw new IllegalStateException("native backend not packaged for "+target);bytes=input.readAllBytes();}
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));Path dir=Path.of(System.getProperty("java.io.tmpdir"),"physicalist-native",hash);Files.createDirectories(dir);Path library=dir.resolve(name);if(!Files.exists(library)||!java.util.Arrays.equals(Files.readAllBytes(library),bytes))Files.write(library,bytes);
  return library.toAbsolutePath();
 }
 public static native int abi0();
 public static native long createScene0(double gravity);
 public static native void destroyScene0(long scene);
 public static native long createBody0(long scene,double mass,double gravityScale,long owner,int role,double[] state,double[] geometry);
 public static native void geometry0(long scene,long body,double mass,long owner,int role,double[] geometry);
 public static native void removeBody0(long scene,long body);
 public static native void terrain0(long scene,long key,double[] boxes);
 public static native void sync0(long scene,long[] bodies,double[] state);
 public static native void step0(long scene,double seconds);
 public static native void forces0(long scene,long body,double[] forceAndTorque);
 public static native double[] jointForce0(long scene,long joint);
 public static native void ignore0(long scene,long body,double[] region);
 /** Owner pairs (flat {a,b,...}, UUID low bits|1) whose colliders never touch: a chain and what it holds. */
 public static native void apart0(long scene,long[] pairs);
 /** One step of buoyancy: {water/body density ratio, linear drag, angular drag, surface Y per collision part or NaN}. */
 public static native void water0(long scene,long body,double[] specification);
 public static native double[] read0(long scene,long[] bodies);
 public static native long joint0(long scene,long parent,long child,double[] specification);
 public static native double[] contacts0(long scene,long body);
 /** Removes an impulse joint made by {@link #joint0}. */
 public static native void removeJoint0(long scene,long joint);
 private NativeBridge(){}
}
