package dev.physicalist;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
/** Immutable per-step spatial broad phase. Large objects/queries use a bounded fallback. */
public final class CollisionIndex<T> {
 private final List<T> all,large=new ArrayList<>();private final Map<Long,List<T>> cells=new HashMap<>();private final Function<T,AABB> bounds;
 public CollisionIndex(List<T> objects,Function<T,AABB> bounds){this.all=List.copyOf(objects);this.bounds=bounds;if(all.size()<64)return;
  for(T object:all){AABB b=bounds.apply(object);if(count(b)>512){large.add(object);continue;}visit(b,key->cells.computeIfAbsent(key,k->new ArrayList<>()).add(object));}
 }
 private static int cell(double x){return (int)Math.floor(x/4);}
 private static long count(AABB b){
  if(!Double.isFinite(b.minX)||!Double.isFinite(b.minY)||!Double.isFinite(b.minZ)||!Double.isFinite(b.maxX)||!Double.isFinite(b.maxY)||!Double.isFinite(b.maxZ))throw new IllegalArgumentException("Non-finite bounds");
  long x=(long)cell(b.maxX)-cell(b.minX)+1,y=(long)cell(b.maxY)-cell(b.minY)+1,z=(long)cell(b.maxZ)-cell(b.minZ)+1;
  if(x>4096||y>4096||z>4096)return Long.MAX_VALUE;return x*y*z;
 }
 private static void visit(AABB b,java.util.function.LongConsumer consumer){for(int x=cell(b.minX);x<=cell(b.maxX);x++)for(int y=cell(b.minY);y<=cell(b.maxY);y++)for(int z=cell(b.minZ);z<=cell(b.maxZ);z++)consumer.accept(BlockPos.asLong(x,y,z));}
 public List<T> query(AABB box){if(all.size()<64||count(box)>4096)return all;Set<T> seen=Collections.newSetFromMap(new IdentityHashMap<>());List<T> result=new ArrayList<>();
  java.util.function.Consumer<T> add=o->{if(seen.add(o)&&bounds.apply(o).intersects(box))result.add(o);};large.forEach(add);visit(box,k->{var bucket=cells.get(k);if(bucket!=null)bucket.forEach(add);});return result;
 }
}
