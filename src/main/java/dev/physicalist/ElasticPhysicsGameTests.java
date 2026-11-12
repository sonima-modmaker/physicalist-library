package dev.physicalist;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder(PhysicalistLibrary.MOD_ID) @PrefixGameTestTemplate(false)
public final class ElasticPhysicsGameTests {
 @GameTest(template="empty") public static void springConservesMomentumAndLimitsStayFinite(GameTestHelper h){
  var a=new RigidBodyState(Vec3.ZERO,new Vec3(.2,.2,.2),2);var b=new RigidBodyState(new Vec3(2,0,0),new Vec3(.2,.2,.2),1);
  var joint=new ElasticJoint(a,b,Vec3.ZERO,Vec3.ZERO,1,.001,.4,Double.POSITIVE_INFINITY);
  a.velocity=new Vec3(.2,0,0);b.velocity=new Vec3(-.1,0,0);Vec3 momentum=a.velocity.scale(2).add(b.velocity);
  joint.beginStep();for(int i=0;i<12;i++)joint.solve(.25);joint.damp();
  h.assertTrue(joint.error()<.02,"Spring failed to converge");h.assertTrue(a.velocity.scale(2).add(b.velocity).distanceTo(momentum)<1e-7,"Damping creates momentum");
  var fixed=new RigidBodyState(new Vec3(4,0,0),new Vec3(.2,.2,.2),0);Vec3 position=fixed.position;fixed.integrate(.5,new Vec3(0,-1,0));fixed.impulse(new Vec3(10,0,0),fixed.position);h.assertTrue(fixed.position.equals(position)&&fixed.velocity.equals(Vec3.ZERO),"Static anchor moved");
  var limit=ElasticJoint.socket(a,b,Vec3.ZERO,Vec3.ZERO,0,.2).limit(new Vec3(0,1,0),new Vec3(0,-1,0),Math.PI/4);
  limit.beginStep();for(int i=0;i<100;i++)limit.solve(.25);h.assertTrue(a.orientation.isFinite()&&b.orientation.isFinite(),"Antipodal angular limit produced NaN");
  h.assertTrue(Math.acos(Math.clamp(a.vector(new Vec3(0,1,0)).dot(b.vector(new Vec3(0,-1,0))),-1,1))<Math.PI/4+.03,"Cone limit did not converge");h.succeed();
 }
 @GameTest(template="empty") public static void springOscillationDampsWithoutDivergence(GameTestHelper h){
  var anchor=new RigidBodyState(Vec3.ZERO,new Vec3(.1,.1,.1),0);var weight=new RigidBodyState(new Vec3(2,0,0),new Vec3(.2,.2,.2),1);
  var spring=new ElasticJoint(anchor,weight,Vec3.ZERO,Vec3.ZERO,1,.02,.15,Double.POSITIVE_INFINITY);
  for(int i=0;i<240;i++)JointSolver.step(List.of(anchor,weight),List.of(spring),Vec3.ZERO,.25,6,()->{});
  h.assertTrue(Math.abs(weight.position.length()-1)<.005&&weight.velocity.length()<.01,"Damped spring failed to settle");
  weight.position=anchor.position;spring.beginStep();for(int i=0;i<10;i++)spring.solve(.25);h.assertTrue(weight.position.length()>.1,"Coincident distance anchors cannot separate");h.succeed();
 }
 @GameTest(template="empty") public static void breakableSpringAndInvalidInputs(GameTestHelper h){
  var a=new RigidBodyState(Vec3.ZERO,new Vec3(.2,.2,.2),0);var b=new RigidBodyState(new Vec3(10,0,0),new Vec3(.2,.2,.2),1);
  var joint=new ElasticJoint(a,b,Vec3.ZERO,Vec3.ZERO,1,0,.2,.1);joint.beginStep();joint.solve(.25);h.assertTrue(joint.broken(),"Break force ignored");
  Vec3 before=b.position;joint.solve(.25);h.assertTrue(before.equals(b.position),"Broken joint still constrains body");
  boolean rejected=false;try{new RigidBodyState(Vec3.ZERO,new Vec3(0,1,1),1);}catch(IllegalArgumentException ex){rejected=true;}h.assertTrue(rejected,"Zero geometry accepted");h.succeed();
 }
 @GameTest(template="empty") public static void broadPhaseMatchesBruteForceAndCutsCandidates(GameTestHelper h){
  List<AABB> boxes=new ArrayList<>();for(int x=-20;x<20;x++)for(int z=-20;z<20;z++)boxes.add(new AABB(x*3,0,z*3,x*3+1,1,z*3+1));
  var index=new CollisionIndex<>(boxes,b->b);var random=new Random(19);long total=0;
  for(int i=0;i<200;i++){double x=random.nextDouble()*120-60,z=random.nextDouble()*120-60;AABB query=new AABB(x,-1,z,x+2,2,z+2);var result=index.query(query);total+=result.size();for(var b:boxes)if(b.intersects(query))h.assertTrue(result.contains(b),"Broadphase missed collision");}
  h.assertTrue(total<boxes.size()*200/100,"Spatial index does not reduce candidates");
  var huge=new AABB(-30000000,-30000000,-30000000,30000000,30000000,30000000);h.assertTrue(index.query(huge).size()==boxes.size(),"Oversized-query fallback lost objects");h.succeed();
 }
 @GameTest(template="empty") public static void velocityJointCannotCreateEnergy(GameTestHelper h){
  var a=new RigidBodyState(Vec3.ZERO,new Vec3(.2,.2,.2),2);var b=new RigidBodyState(new Vec3(1,0,0),new Vec3(.2,.2,.2),1);
  a.velocity=new Vec3(.3,.1,-.2);b.velocity=new Vec3(-.2,.15,.1);a.angular=new Vec3(.1,-.2,.3);b.angular=new Vec3(-.15,.25,-.1);
  Vec3 momentum=a.velocity.scale(2).add(b.velocity);double before=kinetic(a,2)+kinetic(b,1);
  var joint=ElasticJoint.socket(a,b,new Vec3(.5,0,0),new Vec3(-.5,0,0),0,.2);
  for(int i=0;i<12;i++)joint.solveVelocity(1);
  h.assertTrue(a.velocity.scale(2).add(b.velocity).distanceTo(momentum)<1e-9,"Velocity joint creates linear momentum");
  h.assertTrue(kinetic(a,2)+kinetic(b,1)<=before+1e-8,"Velocity joint creates kinetic energy");h.succeed();
 }
 private static double kinetic(RigidBodyState b,double mass){return .5*mass*b.velocity.lengthSqr()+.5*mass*(.2*.2+.2*.2)/3*b.angular.lengthSqr();}
 @GameTest(template="empty") public static void foldedRagdollArmsSeparateWithoutExplosiveSpin(GameTestHelper h){
  var level=h.getLevel();var origin=h.absolutePos(new BlockPos(1,100,1));
  for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk((origin.getX()>>4)+x,(origin.getZ()>>4)+z);
  var player=h.makeMockPlayer(GameType.CREATIVE);var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(player,origin.getCenter());
  for(var limb:doll.limbs())h.assertTrue(limb.angular.equals(Vec3.ZERO),"Spawn injects artificial spin");
  var torso=doll.limbs().get(1);var left=doll.limbs().get(2);var right=doll.limbs().get(3);
  left.orientation.rotationZ((float)(Math.PI/2));right.orientation.rotationZ((float)(-Math.PI/2));
  left.position=torso.point(new Vec3(-.25,.3,0)).subtract(left.vector(new Vec3(.125,.3,0)));
  right.position=torso.point(new Vec3(.25,.3,0)).subtract(right.vector(new Vec3(-.125,.3,0)));
  h.assertTrue(CompoundCollision.contact(left.box(),right.box())!=null,"Fixture arms must overlap");
  // Allow two seconds for a deliberately invalid overlapping pose to settle; bound every transient step.
  for(int i=0;i<40;i++){doll.tick();dev.physicalist.nativecore.NativeWorlds.flush(level);for(var limb:doll.limbs())h.assertTrue(limb.angular.length()<1,"Overlapping spawn causes explosive spin");}
  h.assertTrue(!doll.pausedForChunks(),"Test never simulated");
  var contact=CompoundCollision.contact(left.box(),right.box());
  h.assertTrue(contact==null||contact.depth()<.012,"Own limbs pass through each other: "+(contact==null?0:contact.depth()));
  // Impulse joints (0.18.1) leave the falling body tumbling a little after pushing the arms apart (.15-.2 rad/tick);
  // what matters is that it does not explode (checked every tick above) and keeps tumbling only slowly.
  for(var limb:doll.limbs())h.assertTrue(limb.angular.length()<.25,"Folded ragdoll injects angular energy: "+limb.angular.length());
  for(int limb:new int[]{0,2,3,4,5}){var bodyContact=RagdollSelfCollision.contact(doll.limbs(),limb,1,0);h.assertTrue(bodyContact==null||bodyContact.depth()<.02,"Limb passes through torso: "+limb+" depth="+(bodyContact==null?0:bodyContact.depth()));}
  doll.discard();h.succeed();
 }
 @GameTest(template="empty") public static void ragdollSurvivesFallSaveReloadAndTools(GameTestHelper h){
  var level=h.getLevel();var origin=h.absolutePos(new BlockPos(1,80,1));
  for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)level.setBlock(origin.offset(x,-4,z),Blocks.STONE.defaultBlockState(),3);
  var player=h.makeMockPlayer(GameType.CREATIVE);var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(player,origin.getCenter());
  double initial=doll.limbs().get(1).position.y;for(int i=0;i<180;i++){doll.tick();dev.physicalist.nativecore.NativeWorlds.flush(level);}
  h.assertTrue(doll.limbs().get(1).position.y<initial-1,"Ragdoll has no gravity: initial="+initial+" actual="+doll.limbs().get(1).position+" chunkPause="+doll.pausedForChunks()+" sleeping="+doll.sleeping());
  for(var limb:doll.limbs()){h.assertTrue(RigidBodyState.finite(limb.position)&&limb.orientation.isFinite(),"Ragdoll became nonfinite");h.assertTrue(limb.position.y>origin.getY()-4.1,"Ragdoll fell through floor");h.assertTrue(limb.position.distanceTo(doll.position())<3,"Joint exploded");}
  for(int i=0;i<420&&!doll.sleeping();i++){doll.tick();dev.physicalist.nativecore.NativeWorlds.flush(level);}
  h.assertTrue(doll.sleeping(),"Settled ragdoll never sleeps");
  var tag=new CompoundTag();doll.addAdditionalSaveData(tag);var restored=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);restored.setPos(doll.position());restored.readAdditionalSaveData(tag);
  h.assertTrue(restored.owner().getId().equals(player.getUUID())&&restored.limbs().size()==6,"Owner/limbs lost on save");
  for(int i=0;i<6;i++)h.assertTrue(restored.limbs().get(i).position.distanceTo(doll.limbs().get(i).position)<.001,"Saved pose lost");
  h.assertTrue(PhysicalistBodies.find(restored)!=null&&restored.physicalistBody().collisionBoxes().size()==6,"Tool adapter unavailable");restored.physicalistDrag(player,restored.position().add(2,1,0));h.assertTrue(!restored.sleeping(),"Wand did not wake ragdoll");
  Vec3 beforeDrag=doll.position();for(int i=0;i<25;i++){doll.physicalistDrag(player,beforeDrag.add(2,2,0));doll.tick();dev.physicalist.nativecore.NativeWorlds.flush(level);}
  h.assertTrue(doll.getX()>beforeDrag.x+.3||doll.getY()>beforeDrag.y+.3,"Wand velocity never reaches articulated native body");doll.discard();restored.discard();h.succeed();
 }
}
