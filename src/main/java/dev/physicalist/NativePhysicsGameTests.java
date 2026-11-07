package dev.physicalist;
import dev.physicalist.nativecore.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder(PhysicalistLibrary.MOD_ID) @PrefixGameTestTemplate(false)
public final class NativePhysicsGameTests {
 @GameTest(template="empty") public static void tinyNativeRotationsAccumulate(GameTestHelper h){
  var pos=h.absolutePos(new BlockPos(1,8,1));var level=h.getLevel();
  level.setBlock(pos,Blocks.STONE.defaultBlockState(),3);
  var entity=PhysicalBlockEntity.fromBlock(level,pos,level.getBlockState(pos));
  level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
  for(int i=1;i<=100;i++)NativeGeometry.apply(entity.physicalistBody(),entity.position(),new org.joml.Quaternionf().rotationZ(i*.00001f));
  h.assertTrue(Math.abs(entity.roll()-Math.toDegrees(.001))<.0001,"Sub-milliradian native rotations were lost");entity.discard();h.succeed();
 }
 @GameTest(template="empty") public static void nativeSceneSharesStackContactsAndSleeps(GameTestHelper h){
  if(!NativeBridge.AVAILABLE){h.succeed();return;}long scene=NativeBridge.createScene0(9.81);
  try{NativeBridge.terrain0(scene,1,new double[]{-5,-1,-5,5,0,5});double[] box={0,0,0,0,0,0,1,.5,.5,.5,.6,0};long a=NativeBridge.createBody0(scene,1,1,1,-1,state(0,1),box),b=NativeBridge.createBody0(scene,1,1,2,-1,state(0,2.2),box);
   for(int i=0;i<220;i++)NativeBridge.step0(scene,.05);var r=NativeBridge.read0(scene,new long[]{a,b});h.assertTrue(Math.abs(r[1]-.5)<.03&&Math.abs(r[16]-1.5)<.04,"Native stacked bodies penetrated or levitated");h.assertTrue(r[14]==1&&r[29]==1,"Native stack does not sleep");
  }finally{NativeBridge.destroyScene0(scene);}h.succeed();
 }
 @GameTest(template="empty") public static void nativeTerrainInvalidatesAndUnsupportedBodyFalls(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=h.absolutePos(new BlockPos(1,160,1));
  for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk((floor.getX()>>4)+x,(floor.getZ()>>4)+z);
  for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)level.setBlock(floor.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);
  var source=floor.above(4);level.setBlock(source,Blocks.STONE.defaultBlockState(),3);var body=PhysicalBlockEntity.fromBlock(level,source,level.getBlockState(source));level.setBlock(source,Blocks.AIR.defaultBlockState(),3);
  for(int i=0;i<140;i++){body.tick();NativeWorlds.flush(level);}double y=body.getY();h.assertTrue(Math.abs(y-floor.getY()-1.5)<.03,"Native block did not land");
  for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.setBlock(floor.offset(x,0,z),Blocks.AIR.defaultBlockState(),3);
  for(int i=0;i<25;i++){body.tick();NativeWorlds.flush(level);}h.assertTrue(body.getY()<y-1,"Cached removed terrain still supports sleeping body");h.assertTrue(NativeWorlds.enabled(),"Native scene silently fell back");body.discard();h.succeed();
 }
 @GameTest(template="empty") public static void nativeCcdRetainsLargeCoordinatePrecision(GameTestHelper h){
  if(!NativeBridge.AVAILABLE){h.succeed();return;}long scene=NativeBridge.createScene0(0);
  try{double[] p=state(12000000,4);p[7]=300;NativeBridge.terrain0(scene,1,new double[]{12000001,0,-1,12000001.1,8,1});long body=NativeBridge.createBody0(scene,1,0,1,-1,p,new double[]{0,0,0,0,0,0,1,.15,.15,.15,.6,0});NativeBridge.step0(scene,.05);var r=NativeBridge.read0(scene,new long[]{body});h.assertTrue(r[0]<12000001,"Native CCD passed through thin wall");
  }finally{NativeBridge.destroyScene0(scene);}h.succeed();
 }

 @GameTest(template="empty") public static void grabServoCannotLaunchBodyAndSettlesAtCursor(GameTestHelper h){
  Vec3 p=Vec3.ZERO,v=new Vec3(200,-100,50),goal=new Vec3(0,30,0);
  for(int i=0;i<160;i++){
   if(i==40)goal=new Vec3(30,-20,10);
   v=GrabController.velocity(goal.subtract(p),v,.024525).add(0,-.024525,0);
   h.assertTrue(v.length()<=GrabController.MAX_SPEED+1e-6,"Wand accumulated launch velocity");p=p.add(v);
  }
  h.assertTrue(p.distanceTo(goal)<.01&&v.length()<.01,"Wand cannot settle after abrupt cursor movement");h.succeed();
 }

 @GameTest(template="empty") public static void standingPlayerDoesNotDriveNativeSupport(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}
  var level=h.getLevel();var floor=h.absolutePos(new BlockPos(1,160,1));
  for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk((floor.getX()>>4)+x,(floor.getZ()>>4)+z);
  for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.setBlock(floor.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);
  var source=floor.above();level.setBlock(source,Blocks.STONE.defaultBlockState(),3);var e=PhysicalBlockEntity.fromBlock(level,source,level.getBlockState(source));level.setBlock(source,Blocks.AIR.defaultBlockState(),3);level.addFreshEntity(e);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
  try{
   for(int i=0;i<140;i++){e.tick();NativeWorlds.flush(level);}Vec3 rest=e.position();
   player.setPos(e.getX()+.15,e.getY()+.5,e.getZ());h.assertTrue(level.addFreshEntity(player),"Could not register walking test player");
   player.push(e);e.push(player);h.assertTrue(e.getDeltaMovement().lengthSqr()<1e-10,"Vanilla bounding-box shove moves standing support");
   for(int i=0;i<60;i++){
    player.setDeltaMovement(0,-.08,0);player.move(net.minecraft.world.entity.MoverType.SELF,new Vec3(i%2==0?.01:-.01,-.08,0));
    PhysicalistWalkingCollision.pushBlocked(player,new Vec3(.1,0,0),Vec3.ZERO);
    e.tick();NativeWorlds.flush(level);
    h.assertTrue(e.position().distanceTo(rest)<.015&&e.getDeltaMovement().length()<.01,"Walking player kicked native support");
    h.assertTrue(Math.abs(player.getY()-e.getY()-.5)<.015,"Player lost supporting surface");
   }h.succeed();
  }finally{player.discard();e.discard();}
 }

 @GameTest(template="empty") public static void playerWalksAcrossActualAssembledPlatformWithoutDrivingIt(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=h.absolutePos(new BlockPos(1,170,1));for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk((floor.getX()>>4)+x,(floor.getZ()>>4)+z);for(int x=-3;x<=4;x++)for(int z=-3;z<=4;z++)level.setBlock(floor.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);
  var positions=java.util.List.of(floor.above(),floor.above().east(),floor.above().south(),floor.above().east().south());for(var pos:positions)level.setBlock(pos,Blocks.STONE.defaultBlockState(),3);var assembly=PhysicalBlockEntity.fromBlocks(level,positions);for(var pos:positions)level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);level.addFreshEntity(assembly);var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
  try{for(int i=0;i<140;i++){assembly.tick();NativeWorlds.flush(level);}Vec3 start=assembly.position();player.setPos(start.add(-.5,.5,-.5));level.addFreshEntity(player);
   for(int i=0;i<40;i++){player.push(assembly);assembly.push(player);player.move(net.minecraft.world.entity.MoverType.SELF,new Vec3(.025,-.08,.0125));assembly.tick();NativeWorlds.flush(level);h.assertTrue(NativeWorlds.simulates(assembly),"Assembled object does not use native engine");h.assertTrue(assembly.position().distanceTo(start)<.015,"Walking drives assembled platform");h.assertTrue(Math.abs(player.getY()-assembly.getY()-.5)<.02,"Player slips through assembled surface");}h.succeed();
  }finally{player.discard();assembly.discard();}
 }
 @GameTest(template="empty") public static void assemblyKeepsQuaternionThroughVerticalRotation(GameTestHelper h){var pos=h.absolutePos(new BlockPos(1,8,1));var e=PhysicalBlockEntity.fromBlock(h.getLevel(),pos,Blocks.STONE.defaultBlockState());for(int i=0;i<500;i++){var q=new org.joml.Quaternionf().rotateY(.3f).rotateX((float)Math.toRadians(89+i*.004)).rotateZ(.4f);e.physicalistBody().applyNativePose(pos.getCenter(),q);h.assertTrue(Math.abs(q.dot(e.physicalistBody().nativeOrientation()))>.999999,"Assembly orientation drifts through vertical");}e.discard();h.succeed();}
 private static double[] state(double x,double y){return new double[]{x,y,0,0,0,0,1,0,0,0,0,0,0,1,0};}
}
