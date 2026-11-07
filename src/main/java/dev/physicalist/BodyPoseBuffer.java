package dev.physicalist;
import java.util.ArrayDeque;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
/** Atomic position/quaternion snapshots. Never interpolates independent Euler branches. */
public final class BodyPoseBuffer {
 public record Pose(Vec3 position,Quaternionf rotation){}
 private record Sample(long tick,Vec3 position,Vec3 velocity,Quaternionf rotation,Vec3 angular){}
 private final ArrayDeque<Sample> samples=new ArrayDeque<>();
 public boolean empty(){return samples.isEmpty();}
 public void add(long tick,Vec3 p,Vec3 velocity,Quaternionf q,Vec3 angular){
  if(!finite(p)||!finite(velocity)||!finite(angular)||!q.isFinite()||q.lengthSquared()<.5||q.lengthSquared()>1.5)return;
  if(!samples.isEmpty()){
   var last=samples.getLast();if(tick<=last.tick)return;
   if(last.position.add(last.velocity.scale(tick-last.tick)).distanceToSqr(p)>4096)samples.clear();
  }
  samples.addLast(new Sample(tick,p,velocity,new Quaternionf(q).normalize(),angular));while(samples.size()>10)samples.removeFirst();
 }
 public Pose at(double tick){
  if(samples.isEmpty())throw new IllegalStateException("No body pose snapshots");var previous=samples.getFirst();
  if(tick<=previous.tick)return new Pose(previous.position,new Quaternionf(previous.rotation));
  for(var next:samples){if(next.tick>=tick){float t=(float)((tick-previous.tick)/(next.tick-previous.tick));return new Pose(previous.position.lerp(next.position,t),new Quaternionf(previous.rotation).slerp(next.rotation,t).normalize());}previous=next;}
  double ahead=Math.clamp(tick-previous.tick,0,2);Quaternionf q=new Quaternionf(previous.rotation);double speed=previous.angular.length();
  if(speed>1e-9&&ahead>0){Vec3 axis=previous.angular.scale(1/speed);q.premul(new Quaternionf().rotationAxis((float)(speed*ahead),(float)axis.x,(float)axis.y,(float)axis.z)).normalize();}
  return new Pose(previous.position.add(previous.velocity.scale(ahead)),q);
 }
 private static boolean finite(Vec3 p){return Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
}
