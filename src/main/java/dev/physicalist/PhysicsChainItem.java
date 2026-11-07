package dev.physicalist;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;
import net.minecraft.network.chat.Component;
/** First and second right click choose persistent attachment points. */
@net.neoforged.fml.common.EventBusSubscriber(modid=PhysicalistLibrary.MOD_ID)
public final class PhysicsChainItem extends Item {
 public PhysicsChainItem(Properties properties){super(properties);}
 @Override public InteractionResult useOn(UseOnContext context){var p=context.getPlayer();if(p==null)return InteractionResult.FAIL;if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;var level=(ServerLevel)context.getLevel();if(p.isShiftKeyDown()){p.getPersistentData().remove("PhysicalistChainFirst");p.displayClientMessage(Component.literal("Chain selection cleared"),true);return InteractionResult.SUCCESS;}var target=aimed(p);if(target!=null){Vec3 world=ChainEndpoint.block(level,context.getClickLocation()).resolve(level);if(world==null||target.point.distanceToSqr(p.getEyePosition())<world.distanceToSqr(p.getEyePosition()))return selectEntity(level,p,target.entity,target.point);}try{return select(level,p,ChainEndpoint.block(level,context.getClickLocation()));}catch(IllegalStateException e){p.displayClientMessage(Component.literal(e.getMessage()),true);return InteractionResult.FAIL;}}
 @Override public InteractionResultHolder<ItemStack> use(Level world,Player player,InteractionHand hand){
  if(world instanceof ServerLevel level){if(player.isShiftKeyDown())selectEntity(level,player,player,player.position());else{var target=aimed(player);if(target!=null)selectEntity(level,player,target.entity,target.point);}}
  return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),world.isClientSide);
 }
 private record Target(net.minecraft.world.entity.Entity entity,Vec3 point){}
 private static Target aimed(Player player){Vec3 start=player.getEyePosition(),end=start.add(player.getLookAngle().scale(32));double nearest=Double.POSITIVE_INFINITY;Target result=null;var query=player.getBoundingBox().expandTowards(end.subtract(start)).inflate(1);
  for(var e:player.level().getEntities(player,query,e->e.isAlive()&&!e.isSpectator())){var body=PhysicalistBodies.find(e);var boxes=body==null?java.util.List.of(CompoundCollision.box(e.getBoundingBox())):body.collisionBoxes();for(var box:boxes){double t=PhysicalistTargeting.rayFraction(box,start,end);if(t<nearest){nearest=t;result=new Target(e,start.lerp(end,t));}}}return result;
 }
 public InteractionResult selectEntity(ServerLevel level,Player player,net.minecraft.world.entity.Entity target,Vec3 point){return select(level,player,ChainEndpoint.entity(target,point));}
 @net.neoforged.bus.api.SubscribeEvent public static void entityClick(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific event){
  if(!(event.getItemStack().getItem() instanceof PhysicsChainItem item))return;
  if(event.getLevel() instanceof ServerLevel level)item.selectEntity(level,event.getEntity(),event.getTarget(),event.getTarget().position().add(event.getLocalPos()));
  event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
 }
 /** After the local player's own movement and before its position is sent to the server. */
 @net.neoforged.bus.api.SubscribeEvent public static void playerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event){
  var player=event.getEntity();if(player.level().isClientSide&&player.isLocalPlayer()&&!player.isSpectator())PhysicalChainEntity.holdLocalPlayer(player);
 }
 private InteractionResult select(ServerLevel level,Player player,ChainEndpoint point){
  var data=player.getPersistentData();String dimension=level.dimension().location().toString();
  if(!data.contains("PhysicalistChainFirst")||!dimension.equals(data.getString("PhysicalistChainDimension"))){data.put("PhysicalistChainFirst",point.data);data.putString("PhysicalistChainDimension",dimension);player.displayClientMessage(Component.literal("First chain anchor selected; select the second point"),true);return InteractionResult.SUCCESS;}
  var chain=PhysicalistContent.PHYSICAL_CHAIN.get().create(level);
  try{chain.initialize(new ChainEndpoint(data.getCompound("PhysicalistChainFirst")),point);if(!level.addFreshEntity(chain))return InteractionResult.FAIL;data.remove("PhysicalistChainFirst");player.displayClientMessage(Component.literal("Physical chain created. Right click a link with shears to cut it"),true);return InteractionResult.SUCCESS;}catch(IllegalArgumentException ex){player.displayClientMessage(Component.literal(ex.getMessage()),true);return InteractionResult.FAIL;}
 }
}
