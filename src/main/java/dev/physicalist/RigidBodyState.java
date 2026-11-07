package dev.physicalist;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Independent rigid body, in blocks, ticks and relative mass units. No entity allocation required. */
public final class RigidBodyState {
 public Vec3 position, velocity=Vec3.ZERO, angular=Vec3.ZERO;
 public final Quaternionf orientation=new Quaternionf();
 public final Vec3 half;
 public final double inverseMass;
 private final Vec3 inverseInertia;
 public RigidBodyState(Vec3 position,Vec3 half,double mass){
  if(!finite(position)||!finite(half)||half.x<=0||half.y<=0||half.z<=0||!Double.isFinite(mass)||mass<0)throw new IllegalArgumentException("Invalid rigid body");
  this.position=position;this.half=half;inverseMass=mass==0?0:1/mass;
  inverseInertia=mass==0?Vec3.ZERO:new Vec3(3/(mass*(half.y*half.y+half.z*half.z)),3/(mass*(half.x*half.x+half.z*half.z)),3/(mass*(half.x*half.x+half.y*half.y)));
 }
 public static boolean finite(Vec3 v){return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
 public Vec3 vector(Vec3 v){Vector3f r=orientation.transform(new Vector3f((float)v.x,(float)v.y,(float)v.z));return new Vec3(r.x,r.y,r.z);}
 public Vec3 point(Vec3 local){return position.add(vector(local));}
 public Vec3 pointVelocity(Vec3 world){return velocity.add(angular.cross(world.subtract(position)));}
 public Vec3 inverseInertia(Vec3 torque){
  Vector3f t=new Quaternionf(orientation).conjugate().transform(new Vector3f((float)torque.x,(float)torque.y,(float)torque.z));
  return vector(new Vec3(t.x*inverseInertia.x,t.y*inverseInertia.y,t.z*inverseInertia.z));
 }
 public void impulse(Vec3 impulse,Vec3 point){if(!finite(impulse)||!finite(point))throw new IllegalArgumentException("Non-finite impulse");if(inverseMass==0)return;velocity=velocity.add(impulse.scale(inverseMass));angular=angular.add(inverseInertia(point.subtract(position).cross(impulse)));}
 public void turn(Vec3 rotation){if(!finite(rotation))throw new IllegalArgumentException("Non-finite rotation");double a=rotation.length();if(a>1e-9&&inverseMass>0)orientation.premul(new Quaternionf().rotationAxis((float)a,(float)(rotation.x/a),(float)(rotation.y/a),(float)(rotation.z/a))).normalize();}
 public void positionImpulse(Vec3 impulse,Vec3 arm){if(!finite(impulse)||!finite(arm))throw new IllegalArgumentException("Non-finite correction");if(inverseMass==0)return;position=position.add(impulse.scale(inverseMass));turn(inverseInertia(arm.cross(impulse)));}
 public void integrate(double dt,Vec3 acceleration){if(!Double.isFinite(dt)||dt<=0||dt>1||!finite(acceleration))throw new IllegalArgumentException("Invalid timestep");if(inverseMass==0)return;velocity=velocity.add(acceleration.scale(dt));position=position.add(velocity.scale(dt));turn(angular.scale(dt));}
 public void velocityFromPose(Vec3 before,Quaternionf rotation,double dt){
  if(!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Invalid timestep");if(inverseMass==0)return;
  velocity=position.subtract(before).scale(1/dt);Quaternionf delta=new Quaternionf(orientation).mul(new Quaternionf(rotation).conjugate());if(delta.w<0)delta.mul(-1);
  double angle=2*Math.acos(Math.clamp(delta.w,-1,1)),sin=Math.sqrt(Math.max(0,1-delta.w*delta.w));angular=sin<1e-6?Vec3.ZERO:new Vec3(delta.x,delta.y,delta.z).scale(angle/(sin*dt));
 }
 public CompoundCollision.Box box(){return new CompoundCollision.Box(position,new Vec3[]{vector(new Vec3(1,0,0)),vector(new Vec3(0,1,0)),vector(new Vec3(0,0,1))},new double[]{half.x,half.y,half.z});}
}
