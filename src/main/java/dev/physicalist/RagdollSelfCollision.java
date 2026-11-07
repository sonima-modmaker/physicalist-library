package dev.physicalist;
import java.util.List;
import net.minecraft.world.phys.Vec3;
/** Split positional contacts: depenetration changes pose, never creates rebound velocity. */
public final class RagdollSelfCollision {
 public static boolean collides(int a,int b){return a!=b;}
 private static CompoundCollision.Box box(RigidBodyState body,int part,int other){
  var box=body.box();if(other!=1||part==1)return box;
  // Only the small neck/shoulder/hip attachment cap is excluded, not the whole connected limb.
  double low=-body.half.y,high=body.half.y;
  if(part==0)low+=Math.min(.05,body.half.y*.2);else high-=Math.min(.175,body.half.y*.45);
  Vec3 center=body.position.add(body.vector(new Vec3(0,(low+high)*.5,0)));
  return new CompoundCollision.Box(center,box.axes(),new double[]{body.half.x,(high-low)*.5,body.half.z});
 }
 public static CompoundCollision.Contact contact(List<RigidBodyState> bodies,int a,int b,double skin){
  if(!collides(a,b))return null;var first=box(bodies.get(a),a,b);var second=box(bodies.get(b),b,a);
  if(skin>0)first=new CompoundCollision.Box(first.center(),first.axes(),new double[]{first.half()[0]+skin,first.half()[1]+skin,first.half()[2]+skin});
  return CompoundCollision.contact(first,second);
 }
 public static void project(List<RigidBodyState> bodies){
  for(int a=0;a<bodies.size();a++)for(int b=a+1;b<bodies.size();b++){
   if(!collides(a,b))continue;var first=bodies.get(a);var second=bodies.get(b);
   var contact=contact(bodies,a,b,0);if(contact==null||contact.depth()<=.001)continue;
   Vec3 n=contact.normal(),ra=contact.point().subtract(first.position),rb=contact.point().subtract(second.position);
   double weight=first.inverseMass+second.inverseMass+ra.cross(n).dot(first.inverseInertia(ra.cross(n)))+rb.cross(n).dot(second.inverseInertia(rb.cross(n)));
   if(weight<=1e-12)continue;
   Vec3 correction=n.scale(Math.min(.04,(contact.depth()-.001)*.8)/weight);
   first.positionImpulse(correction,ra);second.positionImpulse(correction.scale(-1),rb);
  }
 }
 /** Zero restitution. Only closing contact motion is removed; separating motion is untouched. */
 public static void velocities(List<RigidBodyState> bodies){
  for(int a=0;a<bodies.size();a++)for(int b=a+1;b<bodies.size();b++){
   if(!collides(a,b))continue;var first=bodies.get(a);var second=bodies.get(b);var c=contact(bodies,a,b,.004);if(c==null)continue;
   Vec3 n=c.normal(),ra=c.point().subtract(first.position),rb=c.point().subtract(second.position);
   double closing=first.pointVelocity(c.point()).subtract(second.pointVelocity(c.point())).dot(n);
   double weight=first.inverseMass+second.inverseMass+ra.cross(n).dot(first.inverseInertia(ra.cross(n)))+rb.cross(n).dot(second.inverseInertia(rb.cross(n)));
   if(closing<0&&weight>1e-12){Vec3 impulse=n.scale(-closing/weight);first.impulse(impulse,c.point());second.impulse(impulse.scale(-1),c.point());}
  }
 }
 private RagdollSelfCollision(){}
}
