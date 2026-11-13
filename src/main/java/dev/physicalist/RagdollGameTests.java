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

 /** A block and a limp ragdoll thrown along the ground stop within a few blocks instead of skating like on ice. */
 @GameTest(template="empty",timeoutTicks=200) public static void frictionStopsSliding(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,160,14);
  var source=floor.above(1);level.setBlock(source,Blocks.STONE.defaultBlockState(),3);var body=PhysicalBlockEntity.fromBlock(level,source,level.getBlockState(source));level.setBlock(source,Blocks.AIR.defaultBlockState(),3);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);player.moveTo(floor.getX()+.5,floor.getY()+1,floor.getZ()-4.5,0,0);player.yBodyRot=0;
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(player);
  try{for(int i=0;i<80;i++){body.tick();doll.tick();NativeWorlds.flush(level);}
   double bx=body.getX(),dz=doll.limbs().get(1).position.z;body.setDeltaMovement(new Vec3(.3,body.getDeltaMovement().y,0));for(var p:doll.limbs())p.velocity=p.velocity.add(0,0,.3);
   var log=new StringBuilder(String.format(java.util.Locale.ROOT,"start y%.2f up%.2f | ",doll.limbs().get(1).position.y-floor.getY(),upright(doll)));
   for(int i=0;i<80;i++){body.tick();doll.tick();NativeWorlds.flush(level);if(i%4==0&&i<40||i%16==0)log.append(String.format(java.util.Locale.ROOT,"t%d block%+.2f v%.3f doll%+.2f v%.3f y%.2f w%.2f | ",i,body.getX()-bx,body.getDeltaMovement().horizontalDistance(),doll.limbs().get(1).position.z-dz,doll.limbs().get(1).velocity.horizontalDistance(),doll.limbs().get(1).position.y-floor.getY(),doll.limbs().get(1).angular.length()));}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Friction: {}",log);
   double slid=body.getX()-bx,dollSlid=doll.limbs().get(1).position.z-dz;
   h.assertTrue(slid<2.5&&body.getDeltaMovement().horizontalDistance()<.01,"Block skates: slid "+slid+" "+log);
   h.assertTrue(dollSlid<2.5&&doll.limbs().get(1).velocity.horizontalDistance()<.01,"Ragdoll skates: slid "+dollSlid+" "+log);h.succeed();
  }finally{body.discard();doll.discard();}
 }

 /**
 * A chain hooks the limb it was attached to (torso, arm, leg). Hanging from it, and pulled taut by the wand, the
 * ragdoll stays calm and the hook stays at the chain's length.
 */
 @GameTest(template="empty",timeoutTicks=300) public static void chainOnLimbStaysCalmWhenTaut(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,170,8);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);int[] parts={1,3,4};
  PlayerRagdollEntity[] dolls=new PlayerRagdollEntity[3];PhysicalChainEntity[] chains=new PhysicalChainEntity[3];Vec3[] tops=new Vec3[3];
  for(int k=0;k<3;k++){tops[k]=Vec3.atCenterOf(floor.above(7)).add(-5+k*5,0,0);
   dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(player,tops[k].add(.3,-2.4,0));level.addFreshEntity(dolls[k]);
   var limb=dolls[k].limbs().get(parts[k]);Vec3 hook=parts[k]==1?limb.position.add(0,limb.half.y,0):limb.position.add(limb.vector(new Vec3(0,-limb.half.y,0)));
   var end=ChainEndpoint.entity(dolls[k],hook);h.assertTrue(end.part()==parts[k],"Chain hooked part "+end.part()+" instead of "+parts[k]);
   chains[k]=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chains[k].initialize(ChainEndpoint.world(tops[k]),end);level.addFreshEntity(chains[k]);}
  try{double[] shake=new double[3],pulled=new double[3],stretch=new double[3];var log=new StringBuilder();
   for(int i=0;i<220;i++){
    for(int k=0;k<3;k++)if(i>=120&&i<180)dolls[k].physicalistDrag(player,tops[k].add(3,-1.5,0));
    for(int k=0;k<3;k++){dolls[k].tick();chains[k].tick();}NativeWorlds.flush(level);
    for(int k=0;k<3;k++){var torso=dolls[k].limbs().get(1);double m=0;for(var p:dolls[k].limbs())m=Math.max(m,Math.max(p.angular.length(),p.velocity.subtract(torso.velocity).length()));
     if(i>=90&&i<120)shake[k]=Math.max(shake[k],m);if(i>=150&&i<180)pulled[k]=Math.max(pulled[k],m);
     Vec3 hook=chains[k].endpoint(1).resolve(level);double s=hook==null||!RigidBodyState.finite(hook)?99:hook.distanceTo(tops[k])-chains[k].length();if(i>=60)stretch[k]=Math.max(stretch[k],s);
     if(i%20==0)log.append(String.format(java.util.Locale.ROOT,"p%d t%d m%.3f s%+.2f; ",parts[k],i,m,s));}}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Chain on limb: shake {} pulled {} stretch {} | {}",java.util.Arrays.toString(shake),java.util.Arrays.toString(pulled),java.util.Arrays.toString(stretch),log);
   for(int k=0;k<3;k++){
    h.assertTrue(stretch[k]<.35,"Part "+parts[k]+" pulls off its chain by "+stretch[k]+" "+log);
    h.assertTrue(shake[k]<.4,"Ragdoll hanging by part "+parts[k]+" shakes: "+shake[k]+" "+log);
    h.assertTrue(pulled[k]<.8,"Ragdoll on a taut chain (part "+parts[k]+") goes wild: "+pulled[k]+" "+log);}
   h.succeed();
  }finally{for(int k=0;k<3;k++){chains[k].discard();dolls[k].discard();}}
 }

 /** One server tick as the game runs it: read the step, tick the entities, start the next step on the physics thread. */
 private static void gameTick(net.minecraft.server.level.ServerLevel level,net.minecraft.world.entity.Entity... entities){NativeWorlds.settle(level);for(var e:entities)e.tick();NativeWorlds.launch(level);}

 /** In the game's tick order, blocks dropped on each other and a ragdoll dropped on them stack instead of sinking in. */
 @GameTest(template="empty",timeoutTicks=200) public static void bodiesDoNotSinkIntoEachOther(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,200,6);
  java.util.List<PhysicalBlockEntity> blocks=new java.util.ArrayList<>();
  for(int k=0;k<3;k++){var at=floor.above(2+k*2);level.setBlock(at,Blocks.STONE.defaultBlockState(),3);var b=PhysicalBlockEntity.fromBlock(level,at,level.getBlockState(at));level.setBlock(at,Blocks.AIR.defaultBlockState(),3);level.addFreshEntity(b);blocks.add(b);}
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(player,Vec3.atCenterOf(floor.above(10)));level.addFreshEntity(doll);
  var all=new java.util.ArrayList<net.minecraft.world.entity.Entity>(blocks);all.add(doll);var array=all.toArray(new net.minecraft.world.entity.Entity[0]);
  try{var log=new StringBuilder();
   for(int i=0;i<120;i++){gameTick(level,array);if(i%10==0){log.append("t").append(i);for(var b:blocks)log.append(String.format(java.util.Locale.ROOT," %.2f",b.getY()-floor.getY()));log.append(String.format(java.util.Locale.ROOT," doll%.2f | ",doll.limbs().get(1).position.y-floor.getY()));}}
   NativeWorlds.settle(level);org.slf4j.LoggerFactory.getLogger("Physicalist").info("Stack: {}",log);
   for(int a=0;a<blocks.size();a++){h.assertTrue(blocks.get(a).getY()-floor.getY()>.9,"Block sinks into the floor "+log);
    for(int b=a+1;b<blocks.size();b++){double depth=0;for(var x:blocks.get(a).physicalistBody().collisionBoxes())for(var y:blocks.get(b).physicalistBody().collisionBoxes()){var c=CompoundCollision.contact(x,y);if(c!=null)depth=Math.max(depth,c.depth());}
     h.assertTrue(depth<.05,"Blocks sink into each other by "+depth+" "+log);}}
   h.succeed();
  }finally{NativeWorlds.settle(level);for(var e:all)e.discard();}
 }

 /** In the game's tick order a chain holds what hangs on it at its length, and drags a block after a walking player. */
 @GameTest(template="empty",timeoutTicks=300) public static void chainHoldsInGameTickOrder(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,190,8);Vec3 top=Vec3.atCenterOf(floor.above(7));
  var source=floor.above(3);level.setBlock(source,Blocks.STONE.defaultBlockState(),3);var block=PhysicalBlockEntity.fromBlock(level,source,level.getBlockState(source));level.setBlock(source,Blocks.AIR.defaultBlockState(),3);level.addFreshEntity(block);
  var hang=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);hang.initialize(ChainEndpoint.world(top),ChainEndpoint.entity(block,block.position().add(0,.5,0)));level.addFreshEntity(hang);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);player.setPos(Vec3.atBottomCenterOf(floor.above()).add(3,0,0));level.addFreshEntity(player);
  var source2=floor.above(1).east(5);level.setBlock(source2,Blocks.STONE.defaultBlockState(),3);var dragged=PhysicalBlockEntity.fromBlock(level,source2,level.getBlockState(source2));level.setBlock(source2,Blocks.AIR.defaultBlockState(),3);level.addFreshEntity(dragged);
  var lead=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);lead.initialize(ChainEndpoint.entity(player,player.position().add(0,1,0)),ChainEndpoint.entity(dragged,dragged.position().add(0,.5,0)));level.addFreshEntity(lead);
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(player,Vec3.atBottomCenterOf(floor.above()).add(3,1.2,2.5));level.addFreshEntity(doll);
  var arm=doll.limbs().get(2);var leash=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);leash.initialize(ChainEndpoint.entity(player,player.position().add(0,1,0)),ChainEndpoint.entity(doll,arm.position.add(arm.vector(new Vec3(0,-arm.half.y,0)))));level.addFreshEntity(leash);
  try{var log=new StringBuilder();double worst=0;
   for(int i=0;i<160;i++){if(i>=60&&i<120)player.setPos(player.position().add(-.15,0,0));gameTick(level,block,hang,dragged,lead,doll,leash);
    if(i%20==0)log.append(String.format(java.util.Locale.ROOT,"doll%.2f/%.2f ",doll.limbs().get(2).position.distanceTo(player.position().add(0,1,0)),leash.length()));
    Vec3 a=hang.endpoint(1).resolve(level);double stretch=a==null?99:a.distanceTo(top)-hang.length();if(i>=40)worst=Math.max(worst,stretch);
    if(i%10==0)log.append(String.format(java.util.Locale.ROOT,"t%d hang%+.2f lead%.2f/%.2f | ",i,stretch,dragged.position().distanceTo(player.position().add(0,1,0)),lead.length()));}
   NativeWorlds.settle(level);org.slf4j.LoggerFactory.getLogger("Physicalist").info("Game-order chains: {}",log);
   h.assertTrue(worst<.3,"Hanging block pulls away from its chain: "+worst+" past the length "+log);
   h.assertTrue(dragged.position().distanceTo(player.position().add(0,1,0))<lead.length()+.8,"Chain on a walking player does not drag the block: "+log);
   h.assertTrue(doll.limbs().get(2).position.distanceTo(player.position().add(0,1,0))<leash.length()+.8,"Chain on a walking player does not drag the ragdoll by its arm: "+log);h.succeed();
  }finally{NativeWorlds.settle(level);doll.discard();leash.discard();hang.discard();lead.discard();block.discard();dragged.discard();player.discard();}
 }

 private static double upright(PlayerRagdollEntity doll){return doll.limbs().get(1).vector(new Vec3(0,1,0)).y;}

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
