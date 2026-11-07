package dev.physicalist;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import dev.physicalist.nativecore.NativeWorlds;
@GameTestHolder(PhysicalistLibrary.MOD_ID) @PrefixGameTestTemplate(false)
public final class ChainGameTests {
 @GameTest(template="empty") public static void articulatedChainSagsCutsAndReloads(GameTestHelper h){
  var level=h.getLevel();Vec3 a=h.absolutePos(new BlockPos(1,150,1)).getCenter(),b=a.add(4,0,0);
  for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(((int)a.x>>4)+x,((int)a.z>>4)+z);
  var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chain.initialize(ChainEndpoint.world(a),ChainEndpoint.world(b));level.addFreshEntity(chain);
  try{
   for(int i=0;i<100;i++){chain.tick();NativeWorlds.flush(level);}
   h.assertTrue(chain.links().get(chain.links().size()/2).position.y<a.y-.1,"Chain remains a rigid line");
   h.assertTrue(chain.links().get(0).point(new Vec3(0,-chain.links().get(0).half.y,0)).distanceTo(a)<.12,"First chain anchor drifted");
   for(int i=1;i<chain.links().size();i++)h.assertTrue(chain.links().get(i-1).point(new Vec3(0,chain.links().get(i-1).half.y,0)).distanceTo(chain.links().get(i).point(new Vec3(0,-chain.links().get(i).half.y,0)))<.12,"Native links separated");
   var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));Vec3 middle=chain.links().get(chain.links().size()/2).position;player.setPos(middle.add(0,0,2));Vec3 aim=middle.subtract(player.getEyePosition()).normalize();player.setYRot((float)Math.toDegrees(Math.atan2(-aim.x,aim.z)));player.setXRot((float)-Math.toDegrees(Math.asin(aim.y)));int hit=chain.nearestLink(player);h.assertTrue(hit>=0,"Cannot aim at chain link");chain.interact(player,net.minecraft.world.InteractionHand.MAIN_HAND);h.assertTrue(chain.cut(hit+1),"Shears did not cut selected joint");
   h.assertTrue(chain.cutAt(0)&&chain.cutAt(chain.links().size()),"Cannot cut end joints");h.assertTrue(!chain.cutAt(0),"Repeated cut must not rebuild joints");
   double before=chain.links().get(0).position.y;for(int i=0;i<30;i++){chain.tick();NativeWorlds.flush(level);}h.assertTrue(chain.links().get(0).position.y<before-2,"Cut chain still pinned to invisible anchor");
   var tag=new net.minecraft.nbt.CompoundTag();chain.saveWithoutId(tag);var restored=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);restored.load(tag);
   h.assertTrue(restored.links().size()==chain.links().size()&&restored.cut(0)&&restored.cut(chain.links().size()),"Chain save lost geometry or cuts");restored.discard();h.succeed();
  }finally{chain.discard();}
 }
 @GameTest(template="empty") public static void chainCanSuspendSharedPhysicalBody(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var pos=h.absolutePos(new BlockPos(1,150,1));
  for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk((pos.getX()>>4)+x,(pos.getZ()>>4)+z);
  var body=PhysicalBlockEntity.fromBlock(level,pos,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());level.addFreshEntity(body);
  var anchor=body.position().add(0,4,0);var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chain.initialize(ChainEndpoint.world(anchor),ChainEndpoint.entity(body,body.position().add(0,.5,0)));level.addFreshEntity(chain);
  try{for(int i=0;i<140;i++){body.tick();chain.tick();NativeWorlds.flush(level);}h.assertTrue(body.getY()>anchor.y-5&&body.getY()<anchor.y-3,"Dynamic body is not constrained by native chain: "+body.position());h.assertTrue(NativeWorlds.enabled(),"Native chain silently fell back");h.succeed();}finally{chain.discard();body.discard();}
 }
 @GameTest(template="empty") public static void ragdollArmIsNotAnAngularBrake(GameTestHelper h){
  var level=h.getLevel();var pos=h.absolutePos(new BlockPos(1,120,1));for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk((pos.getX()>>4)+x,(pos.getZ()>>4)+z);
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE),pos.getCenter());doll.limbs().get(2).angular=new Vec3(0,0,-.12);doll.limbs().get(2).velocity=doll.limbs().get(2).angular.cross(new Vec3(.15,.3,0)).scale(-1);
  try{for(int i=0;i<15;i++){doll.tick();NativeWorlds.flush(level);}h.assertTrue(Math.abs(doll.limbs().get(1).orientation.dot(doll.limbs().get(2).orientation))<.995,"Ragdoll arm behaves like welded stone: "+doll.limbs().get(1).orientation.dot(doll.limbs().get(2).orientation)+" omega="+doll.limbs().get(2).angular+" paused="+doll.pausedForChunks());h.succeed();}finally{doll.discard();}
 }
 @GameTest(template="empty") public static void playersCanPullEachOtherAndCutReleasesThem(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();Vec3 pos=h.absolutePos(new BlockPos(1,160,1)).getCenter();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(((int)pos.x>>4)+x,((int)pos.z>>4)+z);
  var left=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);var right=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);left.setPos(pos);right.setPos(pos.add(4,0,0));level.addFreshEntity(left);level.addFreshEntity(right);var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chain.initialize(ChainEndpoint.entity(left,left.position()),ChainEndpoint.entity(right,right.position()));level.addFreshEntity(chain);
  try{for(int i=0;i<60;i++){chain.tick();NativeWorlds.flush(level);}left.setPos(left.position().add(-.4,0,0));right.setPos(right.position().add(.4,0,0));left.setDeltaMovement(Vec3.ZERO);right.setDeltaMovement(Vec3.ZERO);double l=left.getX(),r=right.getX();for(int i=0;i<4;i++){chain.tick();NativeWorlds.flush(level);}
   h.assertTrue(left.getX()>l+.1&&right.getX()<r-.1,"Chain does not pull both players: "+(left.getX()-l)+" / "+(right.getX()-r));
   h.assertTrue(left.getDeltaMovement().y<=1e-9&&right.getDeltaMovement().y<=1e-9,"Taut chain launched players: "+left.getDeltaMovement()+" / "+right.getDeltaMovement());
   chain.cutAt(0);chain.cutAt(chain.links().size());left.setPos(left.position().add(-1,0,0));right.setPos(right.position().add(1,0,0));left.setDeltaMovement(Vec3.ZERO);right.setDeltaMovement(Vec3.ZERO);l=left.getX();r=right.getX();for(int i=0;i<3;i++){chain.tick();NativeWorlds.flush(level);}h.assertTrue(left.getX()==l&&right.getX()==r&&left.getDeltaMovement().lengthSqr()<1e-10&&right.getDeltaMovement().lengthSqr()<1e-10,"Cut chain keeps pulling players");h.succeed();
  }finally{chain.discard();left.discard();right.discard();}
 }
 @GameTest(template="empty") public static void hangingPlayerSettlesInsteadOfBouncing(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();Vec3 top=h.absolutePos(new BlockPos(1,190,1)).getCenter();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(((int)top.x>>4)+x,((int)top.z>>4)+z);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);player.setPos(top.add(1,-2,0));level.addFreshEntity(player);
  var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chain.initialize(ChainEndpoint.world(top),ChainEndpoint.entity(player,player.position().add(0,1,0)));level.addFreshEntity(chain);
  try{double low=Double.MAX_VALUE,high=-Double.MAX_VALUE,fastest=0;
   // The client integrates the player: gravity and air drag on whatever motion the server last sent.
   for(int i=0;i<240;i++){Vec3 v=player.getDeltaMovement();v=new Vec3(v.x*.91,(v.y-.08)*.98,v.z*.91);player.setDeltaMovement(v);player.setPos(player.position().add(v));chain.tick();NativeWorlds.flush(level);
    if(i>=160){low=Math.min(low,player.getY());high=Math.max(high,player.getY());fastest=Math.max(fastest,Math.abs(player.getDeltaMovement().y));}}
   h.assertTrue(player.getY()>top.y-chain.length()-3,"Chain let the player fall: "+(top.y-player.getY())+" below, chain "+chain.length()+" end "+chain.endpoint(1).entity(level)+" anchor "+chain.anchor(1)+" v "+player.getDeltaMovement());
   h.assertTrue(high-low<.25&&fastest<.12,"Hanging player keeps bouncing: range "+(high-low)+" vertical speed "+fastest);h.succeed();
  }finally{chain.discard();player.discard();}
 }
 /** Falls 6 blocks onto a 3-block chain: caught at the rope length with no upward hop, then hangs. */
 @GameTest(template="empty") public static void fallingPlayerIsCaughtWithoutHop(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();Vec3 top=h.absolutePos(new BlockPos(1,200,1)).getCenter();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(((int)top.x>>4)+x,((int)top.z>>4)+z);
  var player=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);player.setPos(top.add(0,-1.1,0));level.addFreshEntity(player);
  var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chain.initialize(ChainEndpoint.world(top.add(0,2,0)),ChainEndpoint.entity(player,player.position()));level.addFreshEntity(chain);
  try{double rising=0,lowest=Double.MAX_VALUE;boolean caught=false;double start=player.getY();player.setPos(player.position().add(0,3,0));
   for(int i=0;i<100;i++){Vec3 v=player.getDeltaMovement();v=new Vec3(v.x*.91,(v.y-.08)*.98,v.z*.91);player.setDeltaMovement(v);double before=player.getY();player.setPos(player.position().add(v));chain.tick();NativeWorlds.flush(level);
    if(caught)rising=Math.max(rising,player.getY()-before);caught|=player.getY()<start+.2;lowest=Math.min(lowest,player.getY());}
   h.assertTrue(lowest>start-.3,"Chain let the player fall through: "+(start-lowest));h.assertTrue(rising<.05,"Caught player hopped up by "+rising);
   h.assertTrue(player.fallDistance<1,"Hanging player keeps fall damage: "+player.fallDistance);h.succeed();
  }finally{chain.discard();player.discard();}
 }
 /** Two airborne mobs on one chain fall together; the old weight support lifted the lower one and both flew off. */
 @GameTest(template="empty") public static void chainedMobsFallTogether(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();Vec3 pos=h.absolutePos(new BlockPos(1,180,1)).getCenter();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(((int)pos.x>>4)+x,((int)pos.z>>4)+z);
  var upper=net.minecraft.world.entity.EntityType.PIG.create(level);var lower=net.minecraft.world.entity.EntityType.PIG.create(level);upper.setPos(pos);lower.setPos(pos.add(.5,-3,0));level.addFreshEntity(upper);level.addFreshEntity(lower);
  var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chain.initialize(ChainEndpoint.entity(upper,upper.position()),ChainEndpoint.entity(lower,lower.position()));level.addFreshEntity(chain);
  try{double fastest=0;for(int i=0;i<30;i++){upper.tick();lower.tick();chain.tick();NativeWorlds.flush(level);fastest=Math.max(fastest,Math.max(upper.getDeltaMovement().length(),lower.getDeltaMovement().length()));
    h.assertTrue(upper.getDeltaMovement().y<=1e-6&&lower.getDeltaMovement().y<=1e-6,"Chained mob rises at tick "+i+": "+upper.getDeltaMovement()+" / "+lower.getDeltaMovement());}
   h.assertTrue(upper.getY()<pos.y-1&&lower.getY()<pos.y-4,"Chained mobs do not fall: "+upper.position()+" / "+lower.position());
   h.assertTrue(Math.abs(upper.getX()-pos.x)<1&&Math.abs(lower.getX()-pos.x-.5)<1,"Chained mobs fly sideways: "+upper.position()+" / "+lower.position());h.succeed();
  }finally{chain.discard();upper.discard();lower.discard();}
 }
 /** A ragdoll hung from a block swings below it instead of being shot away by its own chain. */
 @GameTest(template="empty") public static void ragdollHangsFromChainWithoutFlyingAway(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();Vec3 top=h.absolutePos(new BlockPos(1,190,1)).getCenter();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(((int)top.x>>4)+x,((int)top.z>>4)+z);
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE),top.add(1,-2,0));level.addFreshEntity(doll);
  var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chain.initialize(ChainEndpoint.world(top),ChainEndpoint.entity(doll,doll.limbs().get(1).position.add(0,.3,0)));level.addFreshEntity(chain);
  try{double fastest=0;for(int i=0;i<160;i++){doll.tick();chain.tick();NativeWorlds.flush(level);if(i>=100)fastest=Math.max(fastest,doll.limbs().get(1).velocity.length());}
   double reach=doll.limbs().get(1).position.distanceTo(top);h.assertTrue(reach<chain.length()+1,"Ragdoll flew off the chain: "+reach+" from the anchor, chain "+chain.length());
   h.assertTrue(doll.limbs().get(1).position.y<top.y,"Ragdoll is not hanging: "+doll.limbs().get(1).position);h.assertTrue(fastest<.3,"Hanging ragdoll keeps flying: "+fastest+" blocks/tick");h.succeed();
  }finally{chain.discard();doll.discard();}
 }
 @GameTest(template="empty") public static void ragdollsCollideWithEachOther(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=h.absolutePos(new BlockPos(1,150,1));for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk((floor.getX()>>4)+x,(floor.getZ()>>4)+z);
  for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)level.setBlock(floor.offset(x,0,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);Vec3 top=floor.getCenter().add(0,.5,0);
  var lower=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);lower.initialize(h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE),top.add(0,.2,0));level.addFreshEntity(lower);
  var upper=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);upper.initialize(h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE),top.add(0,2.5,0));level.addFreshEntity(upper);
  try{double closest=Double.MAX_VALUE;for(int i=0;i<120;i++){lower.tick();upper.tick();NativeWorlds.flush(level);for(var a:lower.limbs())for(var b:upper.limbs())closest=Math.min(closest,a.position.distanceTo(b.position)-Math.min(a.half.x,Math.min(a.half.y,a.half.z))-Math.min(b.half.x,Math.min(b.half.y,b.half.z)));}
   h.assertTrue(closest>-.08,"Ragdolls pass through each other: limbs overlap by "+(-closest)+" (lower "+lower.limbs().get(1).position+", upper "+upper.limbs().get(1).position+")");h.assertTrue(NativeWorlds.enabled(),"Native scene fell back");h.succeed();
  }finally{lower.discard();upper.discard();}
 }
 @GameTest(template="empty") public static void mobRagdollsFallAndSettle(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();var floor=h.absolutePos(new BlockPos(1,130,1));for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk((floor.getX()>>4)+x,(floor.getZ()>>4)+z);
  for(int x=-6;x<=6;x++)for(int z=-3;z<=3;z++)level.setBlock(floor.offset(x,0,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
  java.util.List<PlayerRagdollEntity> dolls=new java.util.ArrayList<>();var types=java.util.List.of(net.minecraft.world.entity.EntityType.PIG,net.minecraft.world.entity.EntityType.COW,net.minecraft.world.entity.EntityType.ZOMBIE,net.minecraft.world.entity.EntityType.CHICKEN,net.minecraft.world.entity.EntityType.SPIDER);
  try{for(int i=0;i<types.size();i++){var mob=types.get(i).create(level);mob.moveTo(floor.getX()-5+i*2.5+.5,floor.getY()+1.6,floor.getZ()+.5,i*70,0);var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(mob);mob.discard();level.addFreshEntity(doll);dolls.add(doll);}
   for(int t=0;t<160;t++){for(var d:dolls)d.tick();NativeWorlds.flush(level);}
   for(var d:dolls){String name=net.minecraft.world.entity.EntityType.getKey(d.mobType()).toString();for(var limb:d.limbs()){h.assertTrue(RigidBodyState.finite(limb.position),name+" ragdoll limb is not finite");h.assertTrue(CompoundCollision.bounds(limb.box()).minY>floor.getY()+.8,name+" ragdoll sank into the floor: "+limb.position);}
    h.assertTrue(d.limbs().get(1).velocity.length()<.05,name+" ragdoll keeps moving: "+d.limbs().get(1).velocity);}
   h.assertTrue(NativeWorlds.enabled(),"Native scene fell back");h.succeed();
  }finally{for(var d:dolls)d.discard();}
 }
 @GameTest(template="empty") public static void nonFiniteRagdollDoesNotStopNativePhysics(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();Vec3 pos=h.absolutePos(new BlockPos(1,175,1)).getCenter();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(((int)pos.x>>4)+x,((int)pos.z>>4)+z);
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE),pos);level.addFreshEntity(doll);
  try{for(int i=0;i<5;i++){doll.tick();NativeWorlds.flush(level);}doll.limbs().get(1).velocity=new Vec3(Double.NaN,0,0);doll.limbs().get(2).position=new Vec3(Double.NaN,0,0);
   for(int i=0;i<5;i++){doll.tick();NativeWorlds.flush(level);}h.assertTrue(NativeWorlds.enabled(),"NaN from one ragdoll switched native physics off");
   for(var limb:doll.limbs())h.assertTrue(RigidBodyState.finite(limb.position)&&RigidBodyState.finite(limb.velocity),"Ragdoll limb stayed non-finite");h.succeed();
  }finally{doll.discard();}
 }
 @GameTest(template="empty") public static void slackChainDoesNotSlowPlayers(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();Vec3 pos=h.absolutePos(new BlockPos(1,170,1)).getCenter();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(((int)pos.x>>4)+x,((int)pos.z>>4)+z);
  var left=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);var right=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);left.setPos(pos);right.setPos(pos.add(4,0,0));level.addFreshEntity(left);level.addFreshEntity(right);var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chain.initialize(ChainEndpoint.entity(left,left.position()),ChainEndpoint.entity(right,right.position()));level.addFreshEntity(chain);
  try{for(int i=0;i<40;i++){left.setPos(left.position().add(.05,0,0));right.setPos(right.position().add(.05,0,0));left.setDeltaMovement(Vec3.ZERO);right.setDeltaMovement(Vec3.ZERO);chain.tick();NativeWorlds.flush(level);
    h.assertTrue(left.getDeltaMovement().lengthSqr()<1e-12&&right.getDeltaMovement().lengthSqr()<1e-12,"Slack chain drags players: "+left.getDeltaMovement()+" / "+right.getDeltaMovement());}
   h.succeed();}finally{chain.discard();left.discard();right.discard();}
 }
 @GameTest(template="empty") public static void drawnChainStaysPinnedWithoutSlidingAlong(GameTestHelper h){
  var level=h.getLevel();Vec3 pos=h.absolutePos(new BlockPos(1,170,1)).getCenter();var player=h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);player.setPos(pos);level.addFreshEntity(player);
  var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);var holder=ChainEndpoint.entity(player,player.position());Vec3 wall=holder.resolve(level).add(4,0,0);chain.initialize(holder,ChainEndpoint.world(wall));level.addFreshEntity(chain);
  try{Vec3[][] rest=chain.visualSegments(1);int n=rest.length;
   // Walking toward the wall: the drawn end follows at once, the slack far half stays where physics left it.
   player.setPos(pos.add(1.5,0,0));player.xo=player.getX();Vec3[][] walk=chain.visualSegments(1);
   h.assertTrue(walk[0][0].distanceTo(holder.resolve(level))<1e-6,"Drawn chain end is not on the player: "+walk[0][0]+" vs "+holder.resolve(level));
   h.assertTrue(walk[n-1][1].distanceTo(wall)<1e-6,"Wall end moved: "+walk[n-1][1]);
   for(int i=n/2;i<n;i++)h.assertTrue(walk[i][0].distanceTo(rest[i][0])<1e-6,"Slack link "+i+" slid along with the player");
   // Lifting fast (several blocks between buffered ticks) still keeps the end attached and the links unstretched.
   player.setPos(pos.add(0,1,0));player.xo=player.getX();player.yo=player.getY();Vec3[][] lift=chain.visualSegments(1);
   h.assertTrue(lift[0][0].distanceTo(holder.resolve(level))<1e-6,"Drawn chain let go of a rising player");
   for(int i=0;i<n;i++)h.assertTrue(lift[i][0].distanceTo(lift[i][1])<chain.links().get(i).half.y*2*1.15,"Link "+i+" overstretched: "+java.util.Arrays.stream(lift).map(s->String.format(java.util.Locale.ROOT,"%.3f",s[0].distanceTo(s[1]))).toList()+" length "+chain.links().get(i).half.y*2+" span "+lift[0][0].distanceTo(wall)+" chain "+chain.length());
   h.succeed();}finally{chain.discard();player.discard();}
 }
 @GameTest(template="empty") public static void ordinaryEntityAnchorUsesLocalCoordinates(GameTestHelper h){
  var entity=new net.minecraft.world.entity.item.ItemEntity(h.getLevel(),1,100,1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));h.getLevel().addFreshEntity(entity);var anchor=ChainEndpoint.entity(entity,entity.position().add(0,.1,0));Vec3 before=anchor.resolve(h.getLevel());entity.setPos(entity.position().add(2,1,0));h.assertTrue(anchor.resolve(h.getLevel()).subtract(before).distanceTo(new Vec3(2,1,0))<.00001,"Ordinary entity anchor is fixed in world");entity.setDeltaMovement(Vec3.ZERO);anchor.applyForce(h.getLevel(),new Vec3(40,0,0),entity.position());h.assertTrue(entity.getDeltaMovement().x>0,"Ordinary entity receives no chain tension");entity.discard();h.succeed();
 }

 @GameTest(template="empty") public static void walkingBodyCanDeflectChainWithoutBeingAnAnchor(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var level=h.getLevel();Vec3 a=h.absolutePos(new BlockPos(1,140,1)).getCenter();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(((int)a.x>>4)+x,((int)a.z>>4)+z);var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);chain.initialize(ChainEndpoint.world(a),ChainEndpoint.world(a.add(4,0,0)));level.addFreshEntity(chain);var player=h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
  try{for(int i=0;i<80;i++){chain.tick();NativeWorlds.flush(level);}var middle=chain.links().get(chain.links().size()/2);player.setPos(middle.position.add(0,-.9,.6));level.addFreshEntity(player);for(int i=0;i<7;i++){player.setDeltaMovement(0,0,-.1);player.setPos(player.position().add(0,0,-.1));chain.tick();NativeWorlds.flush(level);}h.assertTrue(chain.links().stream().anyMatch(p->Math.abs(p.position.z-a.z)>.01),"Player passes through chain without physical reaction");h.succeed();}finally{player.discard();chain.discard();}
 }

}
