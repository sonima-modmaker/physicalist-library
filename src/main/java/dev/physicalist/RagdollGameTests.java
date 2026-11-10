package dev.physicalist;
import dev.physicalist.nativecore.NativeWorlds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
@GameTestHolder(PhysicalistLibrary.MOD_ID) @PrefixGameTestTemplate(false)
public final class RagdollGameTests {
 private static BlockPos floor(GameTestHelper h,int y,int radius){var level=h.getLevel();var floor=h.absolutePos(new BlockPos(1,y,1));
  for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.getChunk((floor.getX()>>4)+x,(floor.getZ()>>4)+z);
  for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)level.setBlock(floor.offset(x,0,z),Blocks.STONE.defaultBlockState(),3);return floor;}

 /** The wand (and any push) acts while the next step already runs on the physics thread; its change must survive. */
 @GameTest(template="empty") public static void pushDuringThreadedStepMovesBody(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,140,12);
  var source=floor.above(1);level.setBlock(source,Blocks.STONE.defaultBlockState(),3);var body=PhysicalBlockEntity.fromBlock(level,source,level.getBlockState(source));level.setBlock(source,Blocks.AIR.defaultBlockState(),3);
  try{for(int i=0;i<40;i++){body.tick();NativeWorlds.flush(level);}double start=body.getX();
   for(int i=0;i<20;i++){body.tick();NativeWorlds.launch(level);body.setDeltaMovement(new Vec3(.25,body.getDeltaMovement().y,0));NativeWorlds.settle(level);}
   h.assertTrue(body.getX()-start>2,"Body held by the wand did not follow it: moved "+(body.getX()-start));h.succeed();}finally{body.discard();}
 }

 /** A punch lands while the step runs on the physics thread and still pushes the block. */
 @GameTest(template="empty") public static void punchPushesPhysicalBlock(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,145,12);
  var source=floor.above(1);level.setBlock(source,Blocks.STONE.defaultBlockState(),3);var body=PhysicalBlockEntity.fromBlock(level,source,level.getBlockState(source));level.setBlock(source,Blocks.AIR.defaultBlockState(),3);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);player.moveTo(body.getX()-2,floor.getY()+1,body.getZ(),-90,20);
  try{for(int i=0;i<40;i++){body.tick();NativeWorlds.flush(level);}double start=body.getX();
   body.tick();NativeWorlds.launch(level);h.assertTrue(body.hurt(level.damageSources().playerAttack(player),1),"Punch was refused");NativeWorlds.settle(level);
   for(int i=0;i<10;i++){body.tick();NativeWorlds.flush(level);}
   h.assertTrue(body.getX()-start>.3,"Punch did not push the block: moved "+(body.getX()-start));h.succeed();}finally{body.discard();}
 }

 private static double upright(PlayerRagdollEntity doll){return doll.limbs().get(1).vector(new Vec3(0,1,0)).y;}

 /** An active ragdoll stands, takes a shove by stepping back, and stays on its feet. */
 @GameTest(template="empty",timeoutTicks=200) public static void activeRagdollKeepsBalance(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,150,10);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);player.moveTo(floor.getX()+.5,floor.getY()+1,floor.getZ()+.5,0,0);player.yBodyRot=0;
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(player,true);
  try{for(int i=0;i<60;i++){doll.tick();NativeWorlds.flush(level);}
   h.assertTrue(!doll.fallen()&&upright(doll)>.9,"Active ragdoll cannot stand: up="+upright(doll)+" fallen="+doll.fallen());
   // Shoved backwards (it faces +z): it steps back, catches itself, and stands still again facing the same way.
   Vec3 start=doll.limbs().get(1).position;double height=start.y;var log=new StringBuilder();
   for(var p:doll.limbs())p.velocity=p.velocity.add(0,0,-.1);
   double lowest=height;for(int i=0;i<120;i++){doll.tick();NativeWorlds.flush(level);var t=doll.limbs().get(1);lowest=Math.min(lowest,t.position.y);
    if(i%6==0)log.append(String.format(java.util.Locale.ROOT,"t%d z%+.2f x%+.2f up%.2f v%.3f arm%.2f | ",i,t.position.z-start.z,t.position.x-start.x,upright(doll),t.velocity.horizontalDistance(),doll.limbs().get(2).vector(new Vec3(0,1,0)).y));}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Backward shove: {}",log);
   var torso=doll.limbs().get(1);Vec3 facing=torso.vector(new Vec3(0,0,1));
   h.assertTrue(!doll.fallen()&&upright(doll)>.85,"Shoved active ragdoll fell: up="+upright(doll)+" torso drop="+(height-lowest)+" "+log);
   h.assertTrue(start.z-torso.position.z>.15&&start.z-torso.position.z<1.6,"Shoved ragdoll did not step back a little: "+(start.z-torso.position.z)+" "+log);
   h.assertTrue(torso.velocity.horizontalDistance()<.015,"Ragdoll keeps walking after the shove: "+torso.velocity.horizontalDistance()+" "+log);
   h.assertTrue(facing.z>.9,"Shove turned the ragdoll around: facing "+facing);
   // The same shove topples a limp one.
   doll.setActive(false);for(var p:doll.limbs())p.velocity=p.velocity.add(.12,0,0);for(int i=0;i<60;i++){doll.tick();NativeWorlds.flush(level);}
   h.assertTrue(upright(doll)<.7,"Limp ragdoll keeps standing: up="+upright(doll));h.succeed();
  }finally{doll.discard();}
 }

 /** With ragdolls on death, a killed mob leaves a ragdoll and is no longer drawn. */
 @GameTest(template="empty") public static void deathLeavesRagdoll(GameTestHelper h){
  var level=h.getLevel();var floor=floor(h,155,3);boolean was=PhysicalistConfig.RAGDOLL_ON_DEATH.get();PhysicalistConfig.RAGDOLL_ON_DEATH.set(true);
  try{var zombie=EntityType.ZOMBIE.create(level);zombie.moveTo(floor.getX()+.5,floor.getY()+1,floor.getZ()+.5,0,0);level.addFreshEntity(zombie);
   var near=new AABB(floor).inflate(4);int before=level.getEntitiesOfClass(PlayerRagdollEntity.class,near).size();
   zombie.hurt(level.damageSources().genericKill(),1000);
   var dolls=level.getEntitiesOfClass(PlayerRagdollEntity.class,near);
   h.assertTrue(dolls.size()==before+1,"Killed zombie left no ragdoll");h.assertTrue(zombie.isInvisible(),"Dead zombie still drawn under its ragdoll");
   h.assertTrue(dolls.stream().anyMatch(d->d.mobType()==EntityType.ZOMBIE),"Ragdoll is not of the zombie");
   for(var d:dolls)d.discard();h.succeed();
  }finally{PhysicalistConfig.RAGDOLL_ON_DEATH.set(was);}
 }
}
