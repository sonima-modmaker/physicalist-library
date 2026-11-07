package dev.physicalist;
import net.minecraft.world.phys.Vec3;
/** Mass-aware force helpers, with impulses in mass*blocks/tick. */
public final class BodyForces {
 public static void impulse(PhysicsBody body,Vec3 impulse,Vec3 point){
  if(!RigidBodyState.finite(impulse)||!RigidBodyState.finite(point))throw new IllegalArgumentException("Non-finite impulse");
  double mass=Math.max(.1,body.aerodynamicMass());var bounds=body.entity().getBoundingBox();
  double inertia=Math.max(.001,mass*(bounds.getXsize()*bounds.getXsize()+bounds.getYsize()*bounds.getYsize()+bounds.getZsize()*bounds.getZsize())/12);
  body.entity().setDeltaMovement(body.entity().getDeltaMovement().add(impulse.scale(1/mass)));
  body.setAngularVelocity(body.angularVelocity().add(point.subtract(body.centerOfMass()).cross(impulse).scale(1/inertia)));body.entity().hasImpulse=true;
 }
 public static void explosion(PhysicsBody body,Vec3 origin,double radius,double strength){
  if(!Double.isFinite(radius)||radius<=0||!Double.isFinite(strength)||strength<0||!RigidBodyState.finite(origin))throw new IllegalArgumentException("Invalid blast");
  Vec3 delta=body.centerOfMass().subtract(origin);double distance=delta.length();if(distance>=radius)return;
  Vec3 direction=distance<1e-8?new Vec3(0,1,0):delta.scale(1/distance);impulse(body,direction.scale(strength*(1-distance/radius)),body.centerOfMass());
 }
 public static void force(PhysicsBody body,Vec3 force,Vec3 point,double dt){if(!Double.isFinite(dt)||dt<=0||dt>1)throw new IllegalArgumentException("Invalid timestep");impulse(body,force.scale(dt),point);}
 private BodyForces(){}
}
