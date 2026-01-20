package com.cam360;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
    private boolean waitingForFrame = false;

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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (captureKey.wasPressed()) {
                startCapture(client);
            }

            if (!capturing || yawIterator == null) return;

            if (!waitingForFrame) {
                // Step 1: rotate player this tick
                if (yawIterator.hasNext()) {
                    float newYaw = yawIterator.next();
                    client.player.setYaw(newYaw);
                    waitingForFrame = true; // next tick we capture
                } else {
                    // Done
                    client.player.setYaw(originalYaw);
                    capturing = false;
                    yawIterator = null;
                    shotIndex = 0;
                    client.player.sendMessage(Text.literal("Captured 360° screenshots!"), false);
                }
            } else {
                // Step 2: capture screenshot on the next tick (after camera updates)
                takeScreenshot(client);
                waitingForFrame = false;
            }
        });

        System.out.println("[Cam360] Client-side mod initialized!");
    }

    private void startCapture(MinecraftClient client) {
        if (capturing || client.player == null) return;

        originalYaw = client.player.getYaw();
        folder = new File(client.runDirectory, "screenshots360");
        if (!folder.exists()) folder.mkdirs();

        List<Float> yawSteps = new ArrayList<>();
        int steps = 8; // 8 shots = every 45°
        for (int i = 0; i < steps; i++) {
            yawSteps.add(originalYaw + (i * 45.0f));
        }

        yawIterator = yawSteps.iterator();
        capturing = true;
        waitingForFrame = false;
        shotIndex = 0;

        client.player.sendMessage(Text.literal("Starting 360° capture..."), false);
    }

    private void takeScreenshot(MinecraftClient client) {
        if (client.getFramebuffer() == null) return;

        String filename = String.format("360_%d_%03d.png", System.currentTimeMillis(), shotIndex++);
        File targetFolder = folder;

        // Ensure we run on the main client thread
        client.execute(() -> {
            ScreenshotRecorder.saveScreenshot(
                    targetFolder,
                    filename,
                    client.getFramebuffer(),
                    text -> {}
            );
        });
    }
}
