package dev.physicalist.nativecore;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
/** Loaded 16-cube sections, greedy merged solids, dirty invalidation and bounded residency. */
public final class TerrainCache {
 private record Tile(long lastUse){}
 private final ServerLevel level;private final long scene;private final Map<Long,Tile> tiles=new HashMap<>();private final Set<Long> dirty=java.util.concurrent.ConcurrentHashMap.newKeySet();private int baked;
 public TerrainCache(ServerLevel level,long scene){this.level=level;this.scene=scene;}
 public void invalidate(BlockPos pos){dirty.add(SectionPos.asLong(pos.getX()>>4,pos.getY()>>4,pos.getZ()>>4));}
 public boolean ensure(AABB area,long tick){int minX=((int)Math.floor(area.minX))>>4,maxX=((int)Math.floor(area.maxX))>>4,minZ=((int)Math.floor(area.minZ))>>4,maxZ=((int)Math.floor(area.maxZ))>>4;
  if((long)(maxX-minX+1)*(maxZ-minZ+1)>256)return false;
  for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(level.getChunkSource().getChunkNow(x,z)==null)return false;
  int minY=Math.max(level.getMinSection(),((int)Math.floor(area.minY))>>4),maxY=Math.min(level.getMaxSection()-1,((int)Math.floor(area.maxY))>>4);
  for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=minY;y<=maxY;y++){long key=SectionPos.asLong(x,y,z);if(!tiles.containsKey(key)||dirty.remove(key)){NativeBridge.terrain0(scene,key,bake(x,y,z));baked++;}tiles.put(key,new Tile(tick));}return true;
 }
 public void prune(long tick){if(tick%20!=0)return;var it=tiles.entrySet().iterator();while(it.hasNext()){var t=it.next();if(tick-t.getValue().lastUse>100){NativeBridge.terrain0(scene,t.getKey(),new double[0]);dirty.remove(t.getKey());it.remove();}}}
 private double[] bake(int sx,int sy,int sz){var chunk=level.getChunkSource().getChunkNow(sx,sz);if(chunk==null)return new double[0];boolean[] full=new boolean[4096];List<AABB> boxes=new ArrayList<>();BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
  for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){pos.set(sx*16+x,sy*16+y,sz*16+z);var state=chunk.getBlockState(pos);if(state.isAir())continue;if(state.isCollisionShapeFullBlock(level,pos))full[x+16*z+256*y]=true;else for(var box:state.getCollisionShape(level,pos).toAabbs())boxes.add(box.move(pos));}
  for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){if(!full[x+16*z+256*y])continue;int dx=1,dz=1,dy=1;while(x+dx<16&&full[x+dx+16*z+256*y])dx++;
   outer:while(z+dz<16){for(int a=0;a<dx;a++)if(!full[x+a+16*(z+dz)+256*y])break outer;dz++;}
   outer:while(y+dy<16){for(int b=0;b<dz;b++)for(int a=0;a<dx;a++)if(!full[x+a+16*(z+b)+256*(y+dy)])break outer;dy++;}
   for(int c=0;c<dy;c++)for(int b=0;b<dz;b++)for(int a=0;a<dx;a++)full[x+a+16*(z+b)+256*(y+c)]=false;boxes.add(new AABB(sx*16+x,sy*16+y,sz*16+z,sx*16+x+dx,sy*16+y+dy,sz*16+z+dz));}
  double[] result=new double[boxes.size()*6];for(int i=0;i<boxes.size();i++){var b=boxes.get(i);double[] v={b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ};System.arraycopy(v,0,result,i*6,6);}return result;
 }
 public int resident(){return tiles.size();}public int baked(){return baked;}
}
