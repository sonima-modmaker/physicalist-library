package dev.physicalist;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder(PhysicalistLibrary.MOD_ID) @PrefixGameTestTemplate(false)
public final class SurfaceAeroGameTests {
 @GameTest(template="empty") public static void noAirflowCannotCreateSpin(GameTestHelper h){
  var wing=AeroSurface.wing(Vec3.ZERO,new Vec3(0,0,1),new Vec3(1,0,0),1,5,0);
  for(Vec3 chord:java.util.List.of(new Vec3(0,0,1),new Vec3(0,1,0),new Vec3(0,-1,0))){
   Vec3 span=new Vec3(1,0,0);var force=SurfaceAerodynamics.surface(wing,Vec3.ZERO,Vec3.ZERO,new Vec3(2,0,0),chord,span,1.225);
   h.assertTrue(force.force().lengthSqr()==0&&force.torque().lengthSqr()==0,"Still vertical wing creates energy");
   var axial=SurfaceAerodynamics.surface(wing,chord.scale(30),Vec3.ZERO,new Vec3(0,2,0),chord,span,1.225);
   h.assertTrue(Math.abs(axial.force().dot(chord.cross(span)))<1e-8,"Zero-incidence axial flow creates lift");
  }h.succeed();
 }
 @GameTest(template="empty") public static void localRotationalAirflowDampsSymmetricFins(GameTestHelper h){
  var wing=AeroSurface.wing(Vec3.ZERO,new Vec3(0,0,1),new Vec3(1,0,0),.4,4,0);Vec3 omega=new Vec3(0,0,2);
  var left=SurfaceAerodynamics.surface(wing,Vec3.ZERO,omega,new Vec3(-1,0,0),new Vec3(0,0,1),new Vec3(1,0,0),1.225);
  var right=SurfaceAerodynamics.surface(wing,Vec3.ZERO,omega,new Vec3(1,0,0),new Vec3(0,0,1),new Vec3(1,0,0),1.225);
  var sum=left.add(right);h.assertTrue(sum.force().length()<1e-9,"Symmetric rotor flow adds translation");h.assertTrue(sum.torque().dot(omega)<0,"Surface torque accelerates its own spin");h.succeed();
 }
 @GameTest(template="empty") public static void authoredWingLiftsStallsAndUsesPointTorque(GameTestHelper h){
  var wing=AeroSurface.wing(Vec3.ZERO,new Vec3(0,0,1),new Vec3(1,0,0),1,6,0);
  var normal=SurfaceAerodynamics.surface(wing,new Vec3(0,-4,40),Vec3.ZERO,new Vec3(2,0,0),new Vec3(0,0,1),new Vec3(1,0,0),1.225);
  var broadside=SurfaceAerodynamics.surface(wing,new Vec3(0,-40,0),Vec3.ZERO,Vec3.ZERO,new Vec3(0,0,1),new Vec3(1,0,0),1.225);
  h.assertTrue(normal.force().y>0&&normal.force().z<0&&normal.torque().z>0,"Wing force/point moment has wrong sign");
  h.assertTrue(Math.abs(broadside.force().z)<1e-8&&broadside.force().y>0,"Stalled broadside wing generates spurious sideways lift");h.succeed();
 }
 @GameTest(template="empty") public static void oppositePropellersCancelReactionTorque(GameTestHelper h){
  var a=PropellerForce.at(new Vec3(-1,0,0),new Vec3(0,1,0),Vec3.ZERO,6000,.4,.1,.01,1.225,1);
  var b=PropellerForce.at(new Vec3(1,0,0),new Vec3(0,1,0),Vec3.ZERO,6000,.4,.1,.01,1.225,-1);
  h.assertTrue(a.add(b).force().y>0&&a.add(b).torque().length()<1e-9,"Paired propellers add unintended roll/yaw");h.succeed();
 }
}
