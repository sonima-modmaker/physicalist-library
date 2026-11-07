package dev.physicalist;
import net.minecraft.world.phys.Vec3;
/** Bounded cursor servo. Velocity, not accumulated impulse, is the controlled quantity. */
public final class GrabController {
 /** Blocks per tick. 1.25 left bodies behind a fast camera turn, out of view. */
 public static final double MAX_SPEED=4;
 public static Vec3 velocity(Vec3 error,Vec3 current,double gravity){
  if(!RigidBodyState.finite(error)||!RigidBodyState.finite(current)||!Double.isFinite(gravity)||gravity<0)throw new IllegalArgumentException("Invalid grab state");
  // Closes ~70% of the gap per tick; complex poles at |0.32| still settle without ringing.
  Vec3 desired=limit(error.scale(.7),MAX_SPEED);
  return limit(current.lerp(desired,.9),MAX_SPEED).add(0,gravity,0);
 }
 private static Vec3 limit(Vec3 v,double cap){return v.lengthSqr()>cap*cap?v.scale(cap/v.length()):v;}
 private GrabController(){}
}
