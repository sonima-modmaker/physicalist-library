package dev.physicalist;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import dev.physicalist.nativecore.NativeWorlds;
@GameTestHolder(PhysicalistLibrary.MOD_ID) @PrefixGameTestTemplate(false)
public final class WaterGameTests {
 /** 5x5x7 water pool; its surface is at pool.getY()+6+8/9 (source water is 8/9 of a block high). */
 private static BlockPos pool(GameTestHelper h,int x){var level=h.getLevel();var base=h.absolutePos(new BlockPos(x,100,1));
  for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++)level.getChunk((base.getX()>>4)+cx,(base.getZ()>>4)+cz);
  for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=-1;dy<=6;dy++){var p=base.offset(dx,dy,dz);boolean wall=Math.abs(dx)==3||Math.abs(dz)==3||dy==-1;level.setBlock(p,wall?Blocks.GLASS.defaultBlockState():Blocks.WATER.defaultBlockState(),2);}
  return base;}
 private static void clear(GameTestHelper h,BlockPos base){for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=-1;dy<=6;dy++)h.getLevel().setBlock(base.offset(dx,dy,dz),Blocks.AIR.defaultBlockState(),2);}
 @GameTest(template="empty",timeoutTicks=200) public static void woodFloatsAndStoneSinks(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var base=pool(h,1);var level=h.getLevel();double surface=base.getY()+6+8/9.0;
  var wood=PhysicalBlockEntity.fromBlock(level,base.offset(-1,1,0),Blocks.OAK_PLANKS.defaultBlockState());var stone=PhysicalBlockEntity.fromBlock(level,base.offset(1,4,0),Blocks.STONE.defaultBlockState());level.addFreshEntity(wood);level.addFreshEntity(stone);
  try{for(int i=0;i<160;i++){wood.tick();stone.tick();NativeWorlds.flush(level);}
   // Oak is 0.6 of water's density: 60% submerged, so the block centre rests 0.1 below the surface.
   h.assertTrue(Math.abs(wood.getY()-(surface-.1))<.15,"Wood did not float at the surface: y="+wood.getY()+" surface="+surface);
   h.assertTrue(stone.getY()<base.getY()+1.2,"Stone did not sink: y="+stone.getY());h.succeed();
  }finally{wood.discard();stone.discard();clear(h,base);}
 }
 @GameTest(template="empty",timeoutTicks=200) public static void ragdollSurfaces(GameTestHelper h){
  if(!NativeWorlds.enabled()){h.succeed();return;}var base=pool(h,12);var level=h.getLevel();
  var doll=PhysicalistContent.PLAYER_RAGDOLL.get().create(level);doll.initialize(h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE),base.offset(0,1,0).getCenter());
  try{for(int i=0;i<160;i++){doll.tick();NativeWorlds.flush(level);}double torso=doll.limbs().get(1).position.y;
   h.assertTrue(torso>base.getY()+4.5,"Ragdoll stayed under water: torso y="+torso+" pool bottom="+base.getY());h.succeed();
  }finally{doll.discard();clear(h,base);}
 }
}
