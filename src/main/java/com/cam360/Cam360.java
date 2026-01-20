package com.cam360;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Cam360 implements ClientModInitializer {

    private static KeyBinding captureKey;

    private boolean capturing = false;
    private boolean screenshotPending = false;

    private Iterator<Float> yawIterator;
    private float originalYaw;
    private File folder;
    private int shotIndex = 0;

    @Override
    public void onInitializeClient() {

        captureKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cam360.capture",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                "category.cam360"
        ));

        // Rotate player & schedule screenshots
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (captureKey.wasPressed()) {
                startCapture(client);
            }

            if (!capturing || yawIterator == null) return;

            if (!screenshotPending) {
                if (yawIterator.hasNext()) {
                    float newYaw = yawIterator.next();
                    client.player.setYaw(newYaw);
                    screenshotPending = true; // next render frame will capture
                } else {
                    client.player.setYaw(originalYaw);
                    capturing = false;
                    yawIterator = null;
                    shotIndex = 0;
                    client.player.sendMessage(Text.literal("Captured 360° screenshots!"), false);
                }
            }
        });

        // SAFE screenshot capture (render thread)
        WorldRenderEvents.END.register(context -> {
            if (!screenshotPending || !capturing) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getFramebuffer() == null) return;

            takeScreenshot(client);
            screenshotPending = false;
        });

        System.out.println("[Cam360] Client-side mod initialized!");
    }

    private void startCapture(MinecraftClient client) {
        if (capturing || client.player == null) return;

        originalYaw = client.player.getYaw();

        folder = new File(client.runDirectory, "screenshots360/screenshots");
        if (!folder.exists()) folder.mkdirs();

        List<Float> yawSteps = new ArrayList<>();
        int steps = 8;
        for (int i = 0; i < steps; i++) {
            yawSteps.add(originalYaw + (i * 45.0f));
        }

        yawIterator = yawSteps.iterator();
        capturing = true;
        screenshotPending = false;
        shotIndex = 0;

        client.player.sendMessage(Text.literal("Starting 360° capture..."), false);
    }

    private void takeScreenshot(MinecraftClient client) {
        String filename = String.format("360_%d_%03d.png",
                System.currentTimeMillis(), shotIndex++);

        ScreenshotRecorder.saveScreenshot(
                folder,
                filename,
                client.getFramebuffer(),
                0,
                text -> {}
        );
    }
}
