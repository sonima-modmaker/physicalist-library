package dev.physicalist;
/** Opt-in main-server-thread counters. Disabled timers do not call nanoTime. */
public final class PhysicsMetrics {
 private static boolean enabled;private static long nanos,steps,queries,candidates,contacts;
 public static void start(){enabled=true;reset();}public static void stop(){enabled=false;}public static void reset(){nanos=steps=queries=candidates=contacts=0;}
 public static long begin(){return enabled?System.nanoTime():0;}
 public static void end(long start){if(start!=0){nanos+=System.nanoTime()-start;steps++;}}
 public static void query(int count){if(enabled){queries++;candidates+=count;}}
 public static void contact(){if(enabled)contacts++;}
 public static String report(){return String.format(java.util.Locale.ROOT,"%s: %d steps, %.3f ms total, %.4f ms/step; %d queries, %d candidates, %d contacts",enabled?"Recording":"Stopped",steps,nanos/1e6,steps==0?0:nanos/1e6/steps,queries,candidates,contacts);}
 private PhysicsMetrics(){}
}
