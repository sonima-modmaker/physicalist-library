package dev.physicalist.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.physicalist.PhysicalistLibrary;
import dev.physicalist.soft.SoftBodyEntity;
import dev.physicalist.soft.SoftPackets;
import dev.physicalist.soft.SoftTimeline;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Client side of soft bodies: the menu, model downloads, node updates, R to start a body over, and flying one. */
@EventBusSubscriber(modid = PhysicalistLibrary.MOD_ID, value = Dist.CLIENT)
public final class SoftBodyClient {
    private static final String CATEGORY = "key.categories.physicalist_library";
    public static final KeyMapping RESET = new KeyMapping("key.physicalist_library.soft_reset", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);
    public static final KeyMapping ENGINE = new KeyMapping("key.physicalist_library.soft_engine", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, CATEGORY);
    public static final KeyMapping BRAKE = new KeyMapping("key.physicalist_library.soft_brake", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY);
    public static final KeyMapping RUDDER_LEFT = new KeyMapping("key.physicalist_library.soft_rudder_left", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY);
    public static final KeyMapping RUDDER_RIGHT = new KeyMapping("key.physicalist_library.soft_rudder_right", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
    private static final Map<Integer, byte[][]> PARTS = new HashMap<>();
    private static final Map<Integer, Object[]> WAITING = new HashMap<>();
    // The pilot's controls.
    private static float pitch, roll, pedals, throttle;
    private static boolean engine;
    private static int seated = -1, seatedTicks;

    public static void openMenu() { Minecraft.getInstance().setScreen(new SoftBodyScreen()); }

    public static void model(SoftPackets.Model p) {
        if (p.total() <= 0 || p.total() > 64 || p.index() < 0 || p.index() >= p.total()) return;
        byte[][] parts = PARTS.computeIfAbsent(p.entity(), k -> new byte[p.total()][]);
        if (parts.length != p.total()) { parts = new byte[p.total()][]; PARTS.put(p.entity(), parts); }
        parts[p.index()] = p.data();
        for (byte[] part : parts) if (part == null) return;
        PARTS.remove(p.entity());
        int size = 0;
        for (byte[] part : parts) size += part.length;
        byte[] blob = new byte[size];
        int at = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, blob, at, part.length); at += part.length; }
        var level = Minecraft.getInstance().level;
        if (level != null && level.getEntity(p.entity()) instanceof SoftBodyEntity e) { e.blob(blob); e.userScale(p.scale()); }
        else WAITING.put(p.entity(), new Object[]{blob, p.scale()});
    }

    public static void state(SoftPackets.State p) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(p.entity()) instanceof SoftBodyEntity e)) return;
        var waiting = WAITING.remove(p.entity());
        if (waiting != null) { e.blob((byte[]) waiting[0]); e.userScale((Double) waiting[1]); }
        e.receive(p);
    }

    @SubscribeEvent
    public static void startTick(ClientTickEvent.Pre event) { SoftTimeline.clientTicks++; }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        while (RESET.consumeClick()) {
            if (mc.player == null || mc.screen != null) continue;
            // Only when there is a soft body around: R stays free for other mods' keys elsewhere.
            if (!mc.player.level().getEntitiesOfClass(SoftBodyEntity.class, new AABB(mc.player.blockPosition()).inflate(128)).isEmpty())
                PacketDistributor.sendToServer(new SoftPackets.Reset());
        }
        if (mc.level == null) { PARTS.clear(); WAITING.clear(); }
        fly(mc);
    }

    /** Keyboard flying: W/S stick forward/back, A/D roll, Z/C rudder, Space/Ctrl throttle, I engine, B brakes. */
    private static void fly(Minecraft mc) {
        if (mc.player == null || !(mc.player.getVehicle() instanceof SoftBodyEntity e)) {
            seated = -1;
            while (ENGINE.consumeClick()) {}
            return;
        }
        if (seated != e.getId()) {
            seated = e.getId();
            seatedTicks = 0;
            pitch = roll = pedals = 0;
        }
        // The server may have started the engine when the plane was made: take over from what it reports.
        if (seatedTicks++ < 6) {
            float[] r = e.rotors();
            if (r.length >= 5) { throttle = r[3]; engine = r[4] > 0.5f; }
            while (ENGINE.consumeClick()) {}
            return;
        }
        boolean free = mc.screen == null;
        float stick = free ? (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0) : 0;
        float bank = free ? (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0) : 0;
        float rudder = free ? (RUDDER_RIGHT.isDown() ? 1 : 0) - (RUDDER_LEFT.isDown() ? 1 : 0) : 0;
        // Keys move the controls smoothly, and they spring back to the middle when let go.
        pitch += (stick - pitch) * 0.3f;
        roll += (bank - roll) * 0.3f;
        pedals += (rudder - pedals) * 0.3f;
        if (free && mc.options.keyJump.isDown()) throttle = Math.min(1, throttle + 0.025f);
        if (free && mc.options.keySprint.isDown()) throttle = Math.max(0, throttle - 0.025f);
        while (ENGINE.consumeClick()) {
            engine = !engine;
            mc.player.displayClientMessage(Component.translatable(engine ? "message.physicalist_library.engine_on" : "message.physicalist_library.engine_off"), true);
        }
        PacketDistributor.sendToServer(new SoftPackets.Controls(e.getId(), pitch, roll, pedals, throttle, engine, free && BRAKE.isDown()));
    }

    /** Instruments while seated: engine, throttle, rpm, thrust, airspeed, height. */
    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !(mc.player.getVehicle() instanceof SoftBodyEntity e)) return;
        float[] r = e.rotors();
        var g = event.getGuiGraphics();
        int y = 8;
        if (r.length >= 5) {
            String state = Component.translatable(engine ? "hud.physicalist_library.on" : "hud.physicalist_library.off").getString();
            g.drawString(mc.font, Component.translatable("hud.physicalist_library.engine", state, Math.round(throttle * 100), Math.round(r[1] * 60 / (2 * Math.PI)),
                    String.format("%.1f", r[2] / 1000)), 8, y, 0xFFFFFF);
            y += 11;
        }
        // A seated player is moved by the plane each tick: how far it moved is the speed.
        var p = mc.player;
        var v = new net.minecraft.world.phys.Vec3(p.getX() - p.xo, p.getY() - p.yo, p.getZ() - p.zo);
        double speed = v.length() * 20 * 3.6;
        double ground = mc.player.getY() - mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, mc.player.getBlockX(), mc.player.getBlockZ());
        g.drawString(mc.font, Component.translatable("hud.physicalist_library.flight", Math.round(speed), Math.round(Math.max(0, ground)), Math.round(v.y * 20)), 8, y, 0xFFFFFF);
        y += 11;
        g.drawString(mc.font, Component.translatable("hud.physicalist_library.keys"), 8, y, 0xA0A0A0);
    }

    private SoftBodyClient() {}
}
