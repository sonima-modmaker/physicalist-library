package dev.physicalist.client;

import dev.physicalist.soft.SoftModel;
import dev.physicalist.soft.SoftModelLoader;
import dev.physicalist.soft.SoftPackets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Soft-body menu: choose a model file or a built-in one, its size and launch speed, then spawn it. */
public final class SoftBodyScreen extends Screen {
    private static final double[] SPEEDS = {0, 10, 20, 40, 60};
    private static final String[] BUILT_IN = {"plane", "car"};
    // Kept between openings.
    private static Path file;
    private static String builtIn = "plane";
    private static double scale = 1;
    private static int speed;
    private static String lastFolder;
    private Component status = Component.empty();
    private boolean busy;

    public SoftBodyScreen() { super(Component.translatable("screen.physicalist_library.soft")); }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 2 - 80;
        addRenderableWidget(Button.builder(Component.translatable("screen.physicalist_library.soft.open"), b -> pick()).bounds(cx - 100, y, 200, 20).build());
        y += 34;
        for (int i = 0; i < BUILT_IN.length; i++) {
            String name = BUILT_IN[i];
            addRenderableWidget(Button.builder(Component.translatable("screen.physicalist_library.soft." + name), b -> { builtIn = name; file = null; status = Component.empty(); })
                    .bounds(cx - 100 + i * 102, y, 98, 20).build());
        }
        y += 26;
        addRenderableWidget(Button.builder(Component.literal("−"), b -> scale = Math.max(0.1, Math.round(scale / 1.25 * 100) / 100.0)).bounds(cx - 100, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> scale = Math.min(10, Math.round(scale * 1.25 * 100) / 100.0)).bounds(cx + 80, y, 20, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(speedLabel(), b -> { speed = (speed + 1) % SPEEDS.length; b.setMessage(speedLabel()); }).bounds(cx - 100, y, 200, 20).build());
        y += 30;
        addRenderableWidget(Button.builder(Component.translatable("screen.physicalist_library.soft.spawn"), b -> spawn()).bounds(cx - 100, y, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(cx + 2, y, 98, 20).build());
    }

    private static Component speedLabel() { return Component.translatable("screen.physicalist_library.soft.speed", (int) SPEEDS[speed]); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = width / 2, y = height / 2 - 80;
        g.drawCenteredString(font, title, cx, y - 20, 0xFFFFFF);
        Component chosen = file != null ? Component.literal(file.getFileName().toString()) : Component.translatable("screen.physicalist_library.soft." + builtIn);
        g.drawCenteredString(font, Component.translatable("screen.physicalist_library.soft.chosen", chosen), cx, y + 22, 0xFFDD88);
        g.drawCenteredString(font, Component.translatable("screen.physicalist_library.soft.scale", String.format("%.2f", scale)), cx, y + 66, 0xFFFFFF);
        g.drawCenteredString(font, Component.translatable("screen.physicalist_library.soft.help"), cx, y + 162, 0xAAAAAA);
        g.drawCenteredString(font, status, cx, y + 176, 0xFF7777);
    }

    private void pick() {
        if (busy) return;
        busy = true;
        status = Component.translatable("screen.physicalist_library.soft.picking");
        String start = lastFolder != null ? lastFolder : Minecraft.getInstance().gameDirectory.getAbsolutePath() + java.io.File.separator;
        // The native dialog blocks: keep it off the render thread so the game keeps drawing.
        var thread = new Thread(() -> {
            String result;
            try (var stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(SoftModelLoader.EXTENSIONS.size());
                for (String ext : SoftModelLoader.EXTENSIONS) filters.put(stack.UTF8("*." + ext));
                filters.flip();
                result = TinyFileDialogs.tinyfd_openFileDialog("Physicalist", start, filters, "GLB, glTF, OBJ, FBX, JSON, bbmodel", false);
            } catch (Throwable error) {
                result = null;
            }
            String picked = result;
            Minecraft.getInstance().execute(() -> {
                busy = false;
                status = Component.empty();
                if (picked == null) return;
                file = Path.of(picked);
                lastFolder = file.getParent() == null ? null : file.getParent().toString() + java.io.File.separator;
            });
        }, "Physicalist model picker");
        thread.setDaemon(true);
        thread.start();
    }

    private void spawn() {
        if (busy) return;
        busy = true;
        status = Component.translatable("screen.physicalist_library.soft.loading");
        Path chosen = file;
        String name = builtIn;
        double s = scale, v = SPEEDS[speed];
        var thread = new Thread(() -> {
            try {
                SoftModel model;
                if (chosen != null) {
                    if (!Files.isRegularFile(chosen)) throw new java.io.IOException("Файл не найден");
                    model = SoftModelLoader.load(SoftModelLoader.files(chosen.toAbsolutePath().getParent()), chosen.getFileName().toString(), minecraftTextures());
                } else model = SoftModelLoader.load(SoftModelLoader.resources("/assets/physicalist_library/softbody"), name + ".obj", null);
                byte[] blob = model.encode();
                Minecraft.getInstance().execute(() -> {
                    busy = false;
                    for (var piece : SoftPackets.pieces(blob, s, v)) PacketDistributor.sendToServer(piece);
                    onClose();
                });
            } catch (Throwable error) {
                String why = error.getMessage() != null ? error.getMessage() : error.toString();
                org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Cannot load soft-body model", error);
                Minecraft.getInstance().execute(() -> { busy = false; status = Component.literal(why.length() > 120 ? why.substring(0, 120) + "…" : why); });
            }
        }, "Physicalist model loader");
        thread.setDaemon(true);
        thread.start();
    }

    /** "minecraft:block/stone" in a JSON model -> that texture from the loaded resource packs. */
    private static Function<String, byte[]> minecraftTextures() {
        var resources = Minecraft.getInstance().getResourceManager();
        return ref -> {
            var id = ResourceLocation.tryParse(ref);
            if (id == null) return null;
            var file = id.withPath("textures/" + id.getPath() + ".png");
            try {
                var resource = resources.getResource(file);
                if (resource.isEmpty()) return null;
                try (var in = resource.get().open()) { return in.readAllBytes(); }
            } catch (Exception error) {
                return null;
            }
        };
    }

    @Override public boolean isPauseScreen() { return false; }
}
