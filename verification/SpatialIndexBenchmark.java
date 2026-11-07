import dev.physicalist.CollisionIndex;
import java.util.*;
import net.minecraft.world.phys.AABB;
/** Fixed synthetic workload; reports CPU time and candidate reduction, never a TPS claim. */
public final class SpatialIndexBenchmark {
 private static volatile long blackhole;
 public static void main(String[] args){
  List<AABB> boxes=new ArrayList<>(),queries=new ArrayList<>();for(int x=-20;x<20;x++)for(int z=-20;z<20;z++)boxes.add(new AABB(x*3,0,z*3,x*3+1,1,z*3+1));
  Random r=new Random(19);for(int i=0;i<2000;i++){double x=r.nextDouble()*120-60,z=r.nextDouble()*120-60;queries.add(new AABB(x,-1,z,x+2,2,z+2));}
  var index=new CollisionIndex<>(boxes,b->b);long count=0;for(int warm=0;warm<8;warm++){for(var q:queries)count+=index.query(q).size();for(var q:queries)for(var b:boxes)if(b.intersects(q))count++;}blackhole=count;
  long start=System.nanoTime(),brute=0;for(var q:queries)for(var b:boxes)if(b.intersects(q))brute++;long bruteTime=System.nanoTime()-start;
  start=System.nanoTime();long indexed=0;for(var q:queries)indexed+=index.query(q).size();long indexTime=System.nanoTime()-start;
  start=System.nanoTime();var rebuilt=new CollisionIndex<>(boxes,b->b);long rebuildTime=System.nanoTime()-start;blackhole=rebuilt.query(queries.getFirst()).size();
  if(brute!=indexed)throw new AssertionError("Broadphase parity lost");
  System.out.printf(Locale.ROOT,"1600 obstacles / 2000 queries: brute %.3f ms, indexed %.3f ms; %d potential pairs -> %d candidates; identical hits=%d; index construction %.3f ms%n",bruteTime/1e6,indexTime/1e6,boxes.size()*queries.size(),indexed,brute,rebuildTime/1e6);
 }
}
