package dev.physicalist.nativecore;
import dev.physicalist.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Matrix3f;
import org.joml.Vector3f;
public final class NativeGeometry {
 public static Quaternionf orientation(PhysicsBody body){Vec3 f=body.forward().normalize(),u=body.up().normalize(),r=u.cross(f).normalize();u=f.cross(r).normalize();return new Quaternionf().setFromNormalized(new Matrix3f().setColumn(0,vector(r)).setColumn(1,vector(u)).setColumn(2,vector(f))).normalize();}
 private static Vector3f vector(Vec3 v){return new Vector3f((float)v.x,(float)v.y,(float)v.z);}
 public static double[] boxes(PhysicsBody body,Quaternionf rotation){var boxes=body.collisionBoxes();double[] out=new double[boxes.size()*12];Quaternionf inverse=new Quaternionf(rotation).conjugate();Vec3 origin=body.entity().position();for(int i=0;i<boxes.size();i++){var box=boxes.get(i);Vector3f pos=inverse.transform(vector(box.center().subtract(origin)));var axes=box.axes();Quaternionf q=new Quaternionf().setFromNormalized(new Matrix3f().setColumn(0,vector(axes[0])).setColumn(1,vector(axes[1])).setColumn(2,vector(axes[2])));inverse.mul(q,q).normalize();int at=i*12;double[] v={pos.x,pos.y,pos.z,q.x,q.y,q.z,q.w,box.half()[0],box.half()[1],box.half()[2],body.profile().surfaceFriction(),body.profile().restitution()};System.arraycopy(v,0,out,at,12);}return out;}
 public static void apply(PhysicsBody body,Vec3 position,Quaternionf target){Quaternionf delta=new Quaternionf(target).mul(orientation(body).conjugate()).normalize();if(delta.w<0)delta.mul(-1);double sin=Math.sqrt((double)delta.x*delta.x+(double)delta.y*delta.y+(double)delta.z*delta.z),angle=2*Math.atan2(sin,delta.w);if(sin>1e-12)body.rotate(new Vec3(delta.x,delta.y,delta.z).scale(angle/sin),1);body.entity().setPos(position);}
 private NativeGeometry(){}
}
