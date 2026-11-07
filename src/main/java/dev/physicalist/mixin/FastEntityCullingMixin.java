package dev.physicalist.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Fast movers are drawn between last and current tick; culling only the current box made held bodies blink out. */
@Mixin(Entity.class)
public abstract class FastEntityCullingMixin {
 @Inject(method="getBoundingBoxForCulling",at=@At("RETURN"),cancellable=true)
 private void physicalist$sweep(CallbackInfoReturnable<AABB> cir){
  var e=(Entity)(Object)this;double dx=e.getX()-e.xOld,dy=e.getY()-e.yOld,dz=e.getZ()-e.zOld;
  if(dx*dx+dy*dy+dz*dz>.04)cir.setReturnValue(cir.getReturnValue().expandTowards(-dx,-dy,-dz));
 }
}
