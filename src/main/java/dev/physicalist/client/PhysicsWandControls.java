package dev.physicalist.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.physicalist.PhysicalistContent;
import dev.physicalist.PhysicalistLibrary;
import dev.physicalist.WandControlPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = PhysicalistLibrary.MOD_ID, value = Dist.CLIENT)
public final class PhysicsWandControls {
    private static boolean holding() {
        var minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.screen == null && minecraft.player.isUsingItem()
                && minecraft.player.getUseItem().is(PhysicalistContent.PHYSICS_WAND.get());
    }

    public static boolean rotate(double dx, double dy) {
        if (!holding()) return false;
        var minecraft = Minecraft.getInstance();
        if (!InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_TAB)) return false;
        minecraft.options.keyPlayerList.setDown(false);
        if (dx != 0 || dy != 0) PacketDistributor.sendToServer(new WandControlPacket(
                WandControlPacket.ROTATE, (float) Math.clamp(dx * .15, -12, 12),
                (float) Math.clamp(dy * .15, -12, 12)));
        return true;
    }

    @SubscribeEvent public static void scroll(InputEvent.MouseScrollingEvent event) {
        if (!holding() || event.getScrollDeltaY() == 0) return;
        PacketDistributor.sendToServer(new WandControlPacket(WandControlPacket.DISTANCE,
                event.getScrollDeltaY() > 0 ? 1 : -1, 0));
        event.setCanceled(true);
    }

    private PhysicsWandControls() {}
}
