package dev.physicalist;

import net.minecraft.world.phys.Vec3;

/** XPBD distance/ball-socket joint. Compliance is inverse stiffness; zero means rigid. */
public final class ElasticJoint {
 public final RigidBodyState a,b;
 public final Vec3 anchorA,anchorB;
 public final double restLength,compliance,damping,breakForce;
 private double lambda;
 private boolean broken;
 private Vec3 axisA,axisB;
 private double maximumAngle=Math.PI;
 public ElasticJoint(RigidBodyState a,RigidBodyState b,Vec3 anchorA,Vec3 anchorB,double restLength,double compliance,double damping,double breakForce){
  if(a==b||!RigidBodyState.finite(anchorA)||!RigidBodyState.finite(anchorB)||!Double.isFinite(restLength)||restLength<0||!Double.isFinite(compliance)||compliance<0||!Double.isFinite(damping)||damping<0||damping>1||Double.isNaN(breakForce)||breakForce<=0)throw new IllegalArgumentException("Invalid joint");
  this.a=a;this.b=b;this.anchorA=anchorA;this.anchorB=anchorB;this.restLength=restLength;this.compliance=compliance;this.damping=damping;this.breakForce=breakForce;
 }
 public static ElasticJoint socket(RigidBodyState a,RigidBodyState b,Vec3 anchorA,Vec3 anchorB,double compliance,double damping){return new ElasticJoint(a,b,anchorA,anchorB,0,compliance,damping,Double.POSITIVE_INFINITY);}
 /** Cone limit in radians; axes are local unit directions. Allows free twist. */
 public ElasticJoint limit(Vec3 axisA,Vec3 axisB,double radians){if(!RigidBodyState.finite(axisA)||!RigidBodyState.finite(axisB)||axisA.lengthSqr()<1e-9||axisB.lengthSqr()<1e-9||!Double.isFinite(radians)||radians<0||radians>Math.PI)throw new IllegalArgumentException("Invalid angular limit");this.axisA=axisA.normalize();this.axisB=axisB.normalize();maximumAngle=radians;return this;}
 private double[] range;
 /**
  * Anatomical range instead of the cone: {twist min, max about b's local y, flex min, max about local x, side min,
  * max about local z}, measured on b's turn relative to a (both share their rest orientation). Null removes it.
  */
 public ElasticJoint range(double[] radians){if(radians!=null&&radians.length!=6)throw new IllegalArgumentException("Invalid joint range");range=radians==null?null:radians.clone();if(range!=null)axisA=null;return this;}
 public double[] range(){return range==null?null:range.clone();}
 /** Turn of b relative to a as {flex (x), twist (y), side (z)} angles, the way the native joint measures them. */
 public static double[] angles(RigidBodyState a,RigidBodyState b){return angles(a.orientation,b.orientation);}
 public static double[] angles(org.joml.Quaternionf a,org.joml.Quaternionf b){
  var q=new org.joml.Quaternionf(a).conjugate().mul(b);if(q.w<0)q.set(-q.x,-q.y,-q.z,-q.w);
  return new double[]{2*Math.atan2(q.x,q.w),2*Math.atan2(q.y,q.w),2*Math.atan2(q.z,q.w)};
 }
 private void solveRange(){
  double[] ang=angles(a,b);double[][] limits={{range[2],range[3]},{range[0],range[1]},{range[4],range[5]}};
  Vec3[] local={new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)};
  for(int i=0;i<3;i++){double over=ang[i]<limits[i][0]?ang[i]-limits[i][0]:ang[i]>limits[i][1]?ang[i]-limits[i][1]:0;if(over==0)continue;
   Vec3 n=a.vector(local[i]);double weight=n.dot(a.inverseInertia(n).add(b.inverseInertia(n)));if(weight<1e-12)continue;
   double impulse=Math.clamp(over,-.15,.15)/weight;a.turn(a.inverseInertia(n).scale(impulse));b.turn(b.inverseInertia(n).scale(-impulse));}
 }
 public boolean broken(){return broken;}
 public void breakJoint(){broken=true;}
 public double error(){return Math.abs(a.point(anchorA).distanceTo(b.point(anchorB))-restLength);}
 public void beginStep(){lambda=0;}
 public void solve(double dt){
  if(!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Invalid timestep");if(broken)return;
  Vec3 pa=a.point(anchorA),pb=b.point(anchorB),delta=pb.subtract(pa);double length=delta.length();
  if(length>1e-9||restLength>0){
   Vec3 n=length>1e-9?delta.scale(1/length):new Vec3(1,0,0);Vec3 ra=pa.subtract(a.position),rb=pb.subtract(b.position);
   double weight=a.inverseMass+b.inverseMass+ra.cross(n).dot(a.inverseInertia(ra.cross(n)))+rb.cross(n).dot(b.inverseInertia(rb.cross(n)));
   double alpha=compliance/(dt*dt);
   if(weight>1e-12){double change=(-(length-restLength)-alpha*lambda)/(weight+alpha);
    if(Math.abs(lambda+change)/(dt*dt)>breakForce){broken=true;return;}
    lambda+=change;a.positionImpulse(n.scale(-change),ra);b.positionImpulse(n.scale(change),rb);
   }
  }
  if(range!=null)solveRange();
  if(axisA!=null){Vec3 u=a.vector(axisA),v=b.vector(axisB);double angle=Math.acos(Math.clamp(u.dot(v),-1,1));
   if(angle>maximumAngle){Vec3 n=u.cross(v);if(n.lengthSqr()<1e-12)n=u.cross(Math.abs(u.y)<.9?new Vec3(0,1,0):new Vec3(1,0,0));n=n.normalize();
    double weight=n.dot(a.inverseInertia(n).add(b.inverseInertia(n)));if(weight>1e-12){double impulse=Math.min(.15,angle-maximumAngle)/weight;a.turn(a.inverseInertia(n).scale(impulse));b.turn(b.inverseInertia(n).scale(-impulse));}
   }
  }
 }
 /** Velocity-level anchor constraint; unlike position reconstruction it cannot turn depenetration into kinetic energy. */
 public void solveVelocity(double strength){
  if(!Double.isFinite(strength)||strength<0||strength>1)throw new IllegalArgumentException("Invalid velocity strength");if(broken)return;
  Vec3 pa=a.point(anchorA),pb=b.point(anchorB),ra=pa.subtract(a.position),rb=pb.subtract(b.position);
  Vec3[] axes=restLength==0?new Vec3[]{new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)}:new Vec3[]{pb.subtract(pa).normalize()};
  for(Vec3 n:axes){double weight=a.inverseMass+b.inverseMass+ra.cross(n).dot(a.inverseInertia(ra.cross(n)))+rb.cross(n).dot(b.inverseInertia(rb.cross(n)));
   if(weight>1e-12){double speed=b.pointVelocity(pb).subtract(a.pointVelocity(pa)).dot(n);Vec3 j=n.scale(speed*strength/weight);a.impulse(j,pa);b.impulse(j.scale(-1),pb);}
  }
  if(axisA!=null){Vec3 u=a.vector(axisA),v=b.vector(axisB);double angle=Math.acos(Math.clamp(u.dot(v),-1,1));Vec3 n=u.cross(v).normalize();
   if(angle>=maximumAngle-.02&&n.lengthSqr()>.5){double speed=b.angular.subtract(a.angular).dot(n),weight=n.dot(a.inverseInertia(n).add(b.inverseInertia(n)));
    if(speed>0&&weight>1e-12){Vec3 impulse=n.scale(speed*strength/weight);a.angular=a.angular.add(a.inverseInertia(impulse));b.angular=b.angular.subtract(b.inverseInertia(impulse));}
   }
  }
 }
 public void damp(){if(broken||damping==0)return;Vec3 pa=a.point(anchorA),pb=b.point(anchorB),delta=pb.subtract(pa);Vec3 relative=b.pointVelocity(pb).subtract(a.pointVelocity(pa));
  Vec3 n=restLength==0?relative.normalize():delta.normalize();if(n.lengthSqr()<1e-12)return;
  Vec3 ra=pa.subtract(a.position),rb=pb.subtract(b.position);double weight=a.inverseMass+b.inverseMass+ra.cross(n).dot(a.inverseInertia(ra.cross(n)))+rb.cross(n).dot(b.inverseInertia(rb.cross(n)));
  if(weight>1e-12){Vec3 j=n.scale(relative.dot(n)*damping/weight);a.impulse(j,pa);b.impulse(j.scale(-1),pb);}
 }
}
