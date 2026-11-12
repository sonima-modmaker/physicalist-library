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
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(player,false);
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

 /**
 * Shoved hard forward, back and sideways, an active ragdoll catches itself by stepping: a planted foot stays where
 * it stands (it does not skate), the body moves by steps, and it ends up standing.
 */
 @GameTest(template="empty",timeoutTicks=300) public static void shovedRagdollStepsInsteadOfSliding(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,220,14);
  Vec3[] pushes={new Vec3(0,0,.2),new Vec3(0,0,-.2),new Vec3(.18,0,0)};String[] names={"forward","back","side"};
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);PlayerRagdollEntity[] dolls=new PlayerRagdollEntity[3];
  for(int k=0;k<3;k++){player.moveTo(floor.getX()+.5-8+k*8,floor.getY()+1,floor.getZ()+.5,0,0);player.yBodyRot=0;dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(player,true);level.addFreshEntity(dolls[k]);}
  try{for(int i=0;i<50;i++){gameTick(level,dolls);}NativeWorlds.settle(level);
   Vec3[] start=new Vec3[3];for(int k=0;k<3;k++){start[k]=dolls[k].limbs().get(1).position;for(var p:dolls[k].limbs())p.velocity=p.velocity.add(pushes[k]);}
   double[] slip=new double[3];int[] steps=new int[3];Vec3[][] last=new Vec3[3][2];int[] was=new int[]{-1,-1,-1};var log=new StringBuilder();
   Vec3[] late=new Vec3[3];
   for(int i=0;i<140;i++){gameTick(level,dolls);NativeWorlds.settle(level);if(i==110)for(int k=0;k<3;k++)late[k]=dolls[k].limbs().get(1).position;
    for(int k=0;k<3;k++){var d=dolls[k];int s=d.steppingLeg();if(s>=0&&s!=was[k])steps[k]++;
     // Skid: how far a planted foot ended up from where it was set down (wobble of the leg about its sole is not).
     for(int f=0;f<2;f++){Vec3 p=d.foot(f);boolean down=s!=f&&was[k]!=f&&!d.fallen()&&p.y<floor.getY()+1.1;
      if(down&&last[k][f]==null)last[k][f]=p;else if((!down||i==139)&&last[k][f]!=null){slip[k]+=Math.hypot(p.x-last[k][f].x,p.z-last[k][f].z);last[k][f]=null;}}
     was[k]=s;if(i%10==0)log.append(String.format(java.util.Locale.ROOT,"%s t%d step%d moved%.2f up%.2f slip%.2f; ",names[k],i,s,d.limbs().get(1).position.subtract(start[k]).horizontalDistance(),upright(d),slip[k]));}}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Shoves: steps {} slip {} | {}",java.util.Arrays.toString(steps),java.util.Arrays.toString(slip),log);
   for(int k=0;k<3;k++){var d=dolls[k];
    h.assertTrue(!d.fallen()&&upright(d)>.85,names[k]+" shove knocked it down "+log);
    h.assertTrue(steps[k]>=1,names[k]+" shove: no step "+log);
    double moved=d.limbs().get(1).position.subtract(start[k]).horizontalDistance();
    h.assertTrue(slip[k]<.25*moved+.15,names[k]+" shove: feet skate "+slip[k]+" for "+moved+" moved "+log);
    h.assertTrue(d.limbs().get(1).position.subtract(late[k]).horizontalDistance()<.4,names[k]+" shove: still walking off "+log);}
   h.succeed();
  }finally{NativeWorlds.settle(level);for(var d:dolls)d.discard();}
 }

 /**
 * Shoved very hard (a player and a villager, back and forward), an active ragdoll never stays stuck half-sitting
 * (torso thrown back, legs out in front) sliding along: it either recovers upright or falls, and in the end stands.
 */
 @GameTest(template="empty",timeoutTicks=400) public static void hardShoveNeverLeavesItHalfSitting(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,230,16);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);double[] pushes={-.3,-.45,.35,-.3};
  PlayerRagdollEntity[] dolls=new PlayerRagdollEntity[4];
  for(int k=0;k<4;k++){Vec3 at=new Vec3(floor.getX()+.5-12+k*8,floor.getY()+1,floor.getZ()+.5);
   if(k==3){var villager=EntityType.VILLAGER.create(level);villager.moveTo(at.x,at.y,at.z,0,0);villager.yBodyRot=0;dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(villager,true);villager.discard();}
   else{player.moveTo(at.x,at.y,at.z,0,0);player.yBodyRot=0;dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(player,true);}
   level.addFreshEntity(dolls[k]);}
  try{for(int i=0;i<50;i++)gameTick(level,dolls);NativeWorlds.settle(level);
   double[] stand=new double[4];for(int k=0;k<4;k++){stand[k]=dolls[k].limbs().get(1).position.y;for(var p:dolls[k].limbs())p.velocity=p.velocity.add(0,0,pushes[k]);}
   int[] stuck=new int[4],worst=new int[4];var log=new StringBuilder();
   for(int i=0;i<300;i++){gameTick(level,dolls);NativeWorlds.settle(level);
    for(int k=0;k<4;k++){var d=dolls[k];double sag=stand[k]-d.limbs().get(1).position.y;boolean half=!d.fallen()&&(upright(d)<.85||sag>.25);stuck[k]=half?stuck[k]+1:0;worst[k]=Math.max(worst[k],stuck[k]);
     if(i%15==0)log.append(String.format(java.util.Locale.ROOT,"d%d t%d up%.2f sag%.2f fallen%s v%.3f; ",k,i,upright(d),sag,d.fallen(),d.limbs().get(1).velocity.horizontalDistance()));}}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Hard shoves: stuck {} | {}",java.util.Arrays.toString(worst),log);
   for(int k=0;k<4;k++){var d=dolls[k];
    h.assertTrue(worst[k]<30,"Ragdoll "+k+" stayed half-sitting for "+worst[k]+" ticks "+log);
    h.assertTrue(!d.fallen()&&upright(d)>.9&&stand[k]-d.limbs().get(1).position.y<.15,"Ragdoll "+k+" is not standing at the end "+log);}
   h.succeed();
  }finally{NativeWorlds.settle(level);for(var d:dolls)d.discard();}
 }

 /** Shoved back against a wall, it does not stay leaning on it straining: it gives up, falls and gets up again. */
 @GameTest(template="empty",timeoutTicks=500) public static void shovedIntoWallDoesNotStayLeaning(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,250,8);
  for(int x=-3;x<=3;x++)for(int y=1;y<=3;y++)level.setBlock(floor.offset(x,y,-1),Blocks.STONE.defaultBlockState(),3);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);player.moveTo(floor.getX()+.5,floor.getY()+1,floor.getZ()+.7,0,0);player.yBodyRot=0;
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(player,true);level.addFreshEntity(doll);
  try{for(int i=0;i<40;i++)gameTick(level,doll);NativeWorlds.settle(level);double stand=doll.limbs().get(1).position.y;
   for(var p:doll.limbs())p.velocity=p.velocity.add(0,0,-.3);
   int run=0,worst=0;var log=new StringBuilder();
   for(int i=0;i<400;i++){gameTick(level,doll);NativeWorlds.settle(level);boolean half=!doll.fallen()&&(upright(doll)<.85||stand-doll.limbs().get(1).position.y>.25);run=half?run+1:0;worst=Math.max(worst,run);
    if(i%20==0)log.append(String.format(java.util.Locale.ROOT,"t%d up%.2f sag%.2f fallen%s; ",i,upright(doll),stand-doll.limbs().get(1).position.y,doll.fallen()));}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Wall: worst {} | {}",worst,log);
   h.assertTrue(worst<45,"Ragdoll stayed leaning on the wall for "+worst+" ticks "+log);
   h.assertTrue(!doll.fallen()&&upright(doll)>.85,"Ragdoll is not standing at the end "+log);h.succeed();
  }finally{NativeWorlds.settle(level);doll.discard();for(int x=-3;x<=3;x++)for(int y=1;y<=3;y++)level.setBlock(floor.offset(x,y,-1),Blocks.AIR.defaultBlockState(),3);}
 }

 /** Knocked onto its back or its face (a player and a villager), an active ragdoll gets up, not stuck sitting. */
 @GameTest(template="empty",timeoutTicks=500) public static void getsUpFromBackAndFront(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,240,16);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);double[] pushes={-.2,.2,-.2,.2};
  PlayerRagdollEntity[] dolls=new PlayerRagdollEntity[4];
  for(int k=0;k<4;k++){Vec3 at=new Vec3(floor.getX()+.5-12+k*8,floor.getY()+1,floor.getZ()+.5);
   if(k>=2){var villager=EntityType.VILLAGER.create(level);villager.moveTo(at.x,at.y,at.z,0,0);villager.yBodyRot=0;dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(villager,true);villager.discard();}
   else{player.moveTo(at.x,at.y,at.z,0,0);player.yBodyRot=0;dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(player,true);}
   level.addFreshEntity(dolls[k]);}
  try{for(int i=0;i<40;i++)gameTick(level,dolls);NativeWorlds.settle(level);double[] stand=new double[4];
   for(int k=0;k<4;k++){stand[k]=dolls[k].limbs().get(1).position.y;dolls[k].setActive(false);for(var p:dolls[k].limbs()){p.velocity=p.velocity.add(0,0,pushes[k]);}dolls[k].limbs().get(0).velocity=dolls[k].limbs().get(0).velocity.add(0,0,pushes[k]);}
   for(int i=0;i<50;i++)gameTick(level,dolls);NativeWorlds.settle(level);
   var log=new StringBuilder();for(int k=0;k<4;k++){log.append(String.format(java.util.Locale.ROOT,"d%d lying up%.2f face%.2f; ",k,upright(dolls[k]),dolls[k].limbs().get(1).vector(new Vec3(0,0,1)).y));dolls[k].setActive(true);}
   int[] standing=new int[]{-1,-1,-1,-1};
   for(int i=0;i<400;i++){gameTick(level,dolls);NativeWorlds.settle(level);
    for(int k=0;k<4;k++){var d=dolls[k];if(standing[k]<0&&!d.fallen()&&upright(d)>.9&&stand[k]-d.limbs().get(1).position.y<.15)standing[k]=i;
     if(i%20==0)log.append(String.format(java.util.Locale.ROOT,"d%d t%d up%.2f sag%.2f fallen%s; ",k,i,upright(d),stand[k]-d.limbs().get(1).position.y,d.fallen()));}}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Get up back/front: standing at {} | {}",java.util.Arrays.toString(standing),log);
   for(int k=0;k<4;k++){var d=dolls[k];h.assertTrue(standing[k]>=0&&standing[k]<200,"Ragdoll "+k+" did not get up in 10 s "+log);
    h.assertTrue(!d.fallen()&&upright(d)>.9,"Ragdoll "+k+" did not stay up "+log);}
   h.succeed();
  }finally{NativeWorlds.settle(level);for(var d:dolls)d.discard();}
 }

 /** An active ragdoll knocked flat lies still a moment, then gets back on its feet. */
 @GameTest(template="empty",timeoutTicks=300) public static void fallenRagdollGetsUp(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,180,10);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);player.moveTo(floor.getX()+.5,floor.getY()+1,floor.getZ()+.5,0,0);player.yBodyRot=0;
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(player,true);
  try{for(int i=0;i<30;i++){doll.tick();NativeWorlds.flush(level);}
   // Knocked down limp, then switched back on lying on the ground.
   doll.setActive(false);for(var p:doll.limbs())p.velocity=p.velocity.add(.15,0,0);for(int i=0;i<50;i++){doll.tick();NativeWorlds.flush(level);}doll.setActive(true);
   var log=new StringBuilder();boolean fell=false;int stood=-1;
   for(int i=0;i<230;i++){doll.tick();NativeWorlds.flush(level);fell|=doll.fallen();if(fell&&!doll.fallen()&&stood<0)stood=i;
    if(i%10==0)log.append(String.format(java.util.Locale.ROOT,"t%d up%.2f y%.2f fallen%s | ",i,upright(doll),doll.limbs().get(1).position.y-floor.getY(),doll.fallen()));}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Get up: {}",log);
   h.assertTrue(fell,"The hard shove did not knock it down "+log);
   h.assertTrue(!doll.fallen()&&upright(doll)>.85,"Fallen ragdoll did not get up: "+log);
   h.assertTrue(doll.limbs().get(1).velocity.horizontalDistance()<.02,"Ragdoll is not standing still after getting up "+log);h.succeed();
  }finally{doll.discard();}
 }

 /**
  * An active ragdoll sent to a point turns, walks there on its own legs and stops. It may stumble and fall once on the
  * way (it gets up and walks on), not more.
  */
 @GameTest(template="empty",timeoutTicks=700) public static void ragdollWalksToGoal(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,210,14);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);Vec3[] goals={new Vec3(0,0,7),new Vec3(6,0,-3)};String[] names={"ahead","behind-side"};
  PlayerRagdollEntity[] dolls=new PlayerRagdollEntity[2];Vec3[] start=new Vec3[2];
  for(int k=0;k<2;k++){player.moveTo(floor.getX()+.5-6+k*12,floor.getY()+1,floor.getZ()+.5-3,0,0);player.yBodyRot=0;dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(player,true);level.addFreshEntity(dolls[k]);}
  try{for(int i=0;i<30;i++)gameTick(level,dolls);NativeWorlds.settle(level);
   for(int k=0;k<2;k++){start[k]=dolls[k].limbs().get(1).position;dolls[k].walkTo(start[k].add(goals[k]),.8,.05);}
   int[] steps=new int[2],was={-1,-1},falls=new int[2];boolean[] fell=new boolean[2],arrived=new boolean[2];var log=new StringBuilder();
   for(int i=0;i<580;i++){gameTick(level,dolls);NativeWorlds.settle(level);
    for(int k=0;k<2;k++){var d=dolls[k];int s=d.steppingLeg();if(s>=0&&s!=was[k])steps[k]++;was[k]=s;if(d.fallen()&&!fell[k])falls[k]++;fell[k]=d.fallen();arrived[k]|=d.atGoal();
     if(i%20==0){var t=d.limbs().get(1);Vec3 to=start[k].add(goals[k]).subtract(t.position);Vec3 f=t.vector(new Vec3(0,0,1));double facing=(f.x*to.x+f.z*to.z)/Math.max(1e-6,Math.hypot(f.x,f.z)*Math.hypot(to.x,to.z));
      log.append(String.format(java.util.Locale.ROOT,"%s t%d left%.2f up%.2f steps%d fallen%s face%.2f v%.3f feet%.2f; ",names[k],i,to.horizontalDistance(),upright(d),steps[k],d.fallen(),facing,t.velocity.horizontalDistance(),d.foot(0).subtract(d.foot(1)).horizontalDistance()));}}}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Walk: {}",log);
   for(int k=0;k<2;k++){var d=dolls[k];double left=d.limbs().get(1).position.subtract(start[k].add(goals[k])).horizontalDistance();
    h.assertTrue(falls[k]<=1,names[k]+": fell "+falls[k]+" times walking "+log);
    h.assertTrue(arrived[k],names[k]+": did not get there "+log);
    h.assertTrue(left<1.4,names[k]+": walked off from the goal, "+left+" left "+log);
    h.assertTrue(steps[k]>=6,names[k]+": slid instead of walking, "+steps[k]+" steps "+log);
    h.assertTrue(upright(d)>.85&&d.atGoal(),names[k]+": not standing still at the goal "+log);}
   h.succeed();
  }finally{NativeWorlds.settle(level);for(var d:dolls)d.discard();}
 }

 /** A dirt block thrown at an active ragdoll makes it stagger (and the block bounces off); a whole stack floors it. */
 @GameTest(template="empty",timeoutTicks=300) public static void thrownBlocksKnockRagdolls(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,260,12);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);PlayerRagdollEntity[] dolls=new PlayerRagdollEntity[2];int[] counts={1,64};
  for(int k=0;k<2;k++){player.moveTo(floor.getX()+.5-4+k*8,floor.getY()+1,floor.getZ()+.5,0,0);player.yBodyRot=0;dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(player,true);level.addFreshEntity(dolls[k]);}
  var items=new net.minecraft.world.entity.item.ItemEntity[2];
  try{for(int i=0;i<30;i++)gameTick(level,dolls);NativeWorlds.settle(level);
   Vec3[] start=new Vec3[2];
   for(int k=0;k<2;k++){start[k]=dolls[k].limbs().get(1).position;Vec3 at=start[k].add(0,.1,2.2);
    items[k]=new net.minecraft.world.entity.item.ItemEntity(level,at.x,at.y,at.z,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT,counts[k]),0,.02,-.45);items[k].setPickUpDelay(40);level.addFreshEntity(items[k]);}
   double[] speed=new double[2];boolean[] fell=new boolean[2];boolean[] bounced=new boolean[2];var log=new StringBuilder();
   for(int i=0;i<120;i++){NativeWorlds.settle(level);for(var it:items)if(it.isAlive())it.tick();for(var d:dolls)d.tick();NativeWorlds.launch(level);NativeWorlds.settle(level);
    for(int k=0;k<2;k++){var d=dolls[k];speed[k]=Math.max(speed[k],d.limbs().get(1).velocity.horizontalDistance());fell[k]|=d.fallen();bounced[k]|=items[k].getDeltaMovement().z>0;
     if(i%10==0)log.append(String.format(java.util.Locale.ROOT,"x%d t%d speed%.3f up%.2f fallen%s item%.2f; ",counts[k],i,d.limbs().get(1).velocity.horizontalDistance(),upright(d),d.fallen(),items[k].getZ()-start[k].z));}}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Thrown: {}",log);
   h.assertTrue(bounced[0]&&bounced[1],"Thrown items flew through "+log);
   h.assertTrue(speed[0]>.04,"One block did not make it stagger "+log);
   h.assertTrue(!fell[0],"One block knocked it over "+log);
   h.assertTrue(fell[1],"A whole stack did not knock it over "+log);
   h.succeed();
  }finally{NativeWorlds.settle(level);for(var d:dolls)d.discard();for(var it:items)if(it!=null)it.discard();}
 }

 private static double upright(PlayerRagdollEntity doll){return doll.limbs().get(1).vector(new Vec3(0,1,0)).y;}

 /**
  * Punched by a player (in the head, in the chest from the side, three times in a row) an active ragdoll staggers and
  * steps but stays on its feet; so it does after one heavy sword-like blow.
  */
 @GameTest(template="empty",timeoutTicks=400) public static void punchesDoNotFloorIt(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,280,14);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);String[] names={"head x3","chest-side x3","heavy"};int[] part={0,1,1};float[] damage={1,1,7};int[] hits={3,3,1};
  Vec3[] from={new Vec3(0,0,-2.5),new Vec3(2.5,0,0),new Vec3(0,0,-2.5)};
  PlayerRagdollEntity[] dolls=new PlayerRagdollEntity[3];
  for(int k=0;k<3;k++){player.moveTo(floor.getX()+.5-8+k*8,floor.getY()+1,floor.getZ()+.5,0,0);player.yBodyRot=0;dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(player,true);level.addFreshEntity(dolls[k]);}
  try{for(int i=0;i<30;i++)gameTick(level,dolls);NativeWorlds.settle(level);
   boolean[] fell=new boolean[3];double[] speed=new double[3];var log=new StringBuilder();
   for(int i=0;i<300;i++){
    for(int k=0;k<3;k++)if(i%30==0&&i/30<hits[k]){var d=dolls[k];Vec3 aim=d.limbs().get(part[k]).position,at=d.limbs().get(1).position.add(from[k]);
     player.moveTo(at.x,floor.getY()+1,at.z,0,0);Vec3 eye=player.getEyePosition(),dir=aim.subtract(eye);
     player.moveTo(at.x,floor.getY()+1,at.z,(float)Math.toDegrees(Math.atan2(-dir.x,dir.z)),(float)-Math.toDegrees(Math.asin(dir.y/dir.length())));
     d.hurt(level.damageSources().playerAttack(player),damage[k]);}
    gameTick(level,dolls);NativeWorlds.settle(level);
    for(int k=0;k<3;k++){var d=dolls[k];fell[k]|=d.fallen();speed[k]=Math.max(speed[k],d.limbs().get(1).velocity.horizontalDistance());
     if(i%10==0)log.append(String.format(java.util.Locale.ROOT,"%s t%d up%.2f v%.3f step%d fallen%s; ",names[k],i,upright(d),d.limbs().get(1).velocity.horizontalDistance(),d.steppingLeg(),d.fallen()));}}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Punches: {}",log);
   for(int k=0;k<3;k++){h.assertTrue(!fell[k],names[k]+": knocked it over "+log);h.assertTrue(speed[k]>.02,names[k]+": did not even stagger "+log);}
   h.succeed();
  }finally{NativeWorlds.settle(level);for(var d:dolls)d.discard();}
 }

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
   doll.setActive(false);for(var p:doll.limbs())p.velocity=p.velocity.add(.15,0,0);for(int i=0;i<80;i++){doll.tick();NativeWorlds.flush(level);}
   h.assertTrue(upright(doll)<.7,"Limp ragdoll keeps standing: up="+upright(doll));h.succeed();
  }finally{doll.discard();}
 }

 /**
  * After a shove an active ragdoll catches itself and then stands still, upright, over its feet: no endless small
  * steps creeping it across the floor.
  */
 @GameTest(template="empty",timeoutTicks=600) public static void standsStillAfterShove(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=floor(h,270,12);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);Vec3[] pushes={new Vec3(0,0,.15),new Vec3(.12,0,0),new Vec3(0,0,-.15)};
  PlayerRagdollEntity[] dolls=new PlayerRagdollEntity[3];
  for(int k=0;k<3;k++){player.moveTo(floor.getX()+.5-6+k*6,floor.getY()+1,floor.getZ()+.5,0,0);player.yBodyRot=0;dolls[k]=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);dolls[k].initialize(player,true);level.addFreshEntity(dolls[k]);}
  try{for(int i=0;i<30;i++)gameTick(level,dolls);NativeWorlds.settle(level);
   for(int k=0;k<3;k++)for(var p:dolls[k].limbs())p.velocity=p.velocity.add(pushes[k]);
   Vec3[] mid=new Vec3[3];int[] late=new int[3],was={-1,-1,-1};double[] tilt=new double[3];var log=new StringBuilder();
   for(int i=0;i<450;i++){gameTick(level,dolls);NativeWorlds.settle(level);
    for(int k=0;k<3;k++){var d=dolls[k];if(i==200)mid[k]=d.limbs().get(1).position;int s=d.steppingLeg();if(i>200&&s>=0&&s!=was[k])late[k]++;was[k]=s;if(i>200)tilt[k]=Math.max(tilt[k],1-upright(d));
     if(i%50==0)log.append(String.format(java.util.Locale.ROOT,"d%d t%d up%.2f v%.3f fallen%s; ",k,i,upright(d),d.limbs().get(1).velocity.horizontalDistance(),d.fallen()));}}
   org.slf4j.LoggerFactory.getLogger("Physicalist").info("Stand: late steps {} | {}",java.util.Arrays.toString(late),log);
   for(int k=0;k<3;k++){var d=dolls[k];double drift=d.limbs().get(1).position.subtract(mid[k]).horizontalDistance();
    h.assertTrue(!d.fallen(),"d"+k+" fell "+log);
    h.assertTrue(drift<.25&&late[k]<=2,"d"+k+" keeps creeping: "+drift+" in "+late[k]+" steps "+log);
    h.assertTrue(tilt[k]<.03,"d"+k+" stands bent: "+tilt[k]+" "+log);
    // Calm again, the arms hang down at its sides (not held out in front, stuck on a wrung shoulder).
    for(int a=2;a<4;a++){double hang=d.limbs().get(a).vector(new Vec3(0,1,0)).y;h.assertTrue(hang>.9,"d"+k+" arm "+a+" is not hanging down: "+hang+" "+log);}}
   h.succeed();
  }finally{NativeWorlds.settle(level);for(var d:dolls)d.discard();}
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
