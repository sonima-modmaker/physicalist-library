package dev.physicalist;
import java.util.List;
import net.minecraft.world.phys.Vec3;
/** Reusable island driver. Supply collision projection after each joint iteration. */
public final class JointSolver {
 public static void step(List<RigidBodyState> bodies,List<ElasticJoint> joints,Vec3 acceleration,double dt,int iterations,Runnable projectCollisions){
  if(!Double.isFinite(dt)||dt<=0||dt>1||iterations<1||iterations>64)throw new IllegalArgumentException("Invalid solver budget");
  Vec3[] previous=new Vec3[bodies.size()];org.joml.Quaternionf[] rotations=new org.joml.Quaternionf[bodies.size()];
  for(int i=0;i<bodies.size();i++){var b=bodies.get(i);previous[i]=b.position;rotations[i]=new org.joml.Quaternionf(b.orientation);b.integrate(dt,acceleration);}for(var j:joints)j.beginStep();
  for(int i=0;i<iterations;i++){for(var j:joints)j.solve(dt);projectCollisions.run();}
  for(int i=0;i<bodies.size();i++)bodies.get(i).velocityFromPose(previous[i],rotations[i],dt);
  for(var j:joints)j.damp();
 }
 private JointSolver(){}
}
