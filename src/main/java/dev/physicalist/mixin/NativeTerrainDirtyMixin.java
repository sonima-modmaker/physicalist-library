package dev.physicalist.mixin;
import dev.physicalist.nativecore.NativeWorlds;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LevelChunk.class)
public abstract class NativeTerrainDirtyMixin {
 @Inject(method="setBlockState",at=@At("RETURN"))
 private void physicalist$dirty(BlockPos pos,BlockState state,boolean moving,CallbackInfoReturnable<BlockState> cir){
  if(cir.getReturnValue()!=null&&((LevelChunk)(Object)this).getLevel() instanceof net.minecraft.server.level.ServerLevel level)NativeWorlds.invalidate(level,pos);
 }
}
