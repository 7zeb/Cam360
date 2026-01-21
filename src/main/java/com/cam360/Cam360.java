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
    private int delayTicks = 0; // 2‑tick delay counter

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

            // Handle the 2‑tick delay
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // If delay is finished, take screenshot
            if (delayTicks == 0 && shotIndex > 0) {
                takeScreenshot(client);
            }

            // Rotate player for next screenshot
            if (yawIterator.hasNext()) {
                float newYaw = yawIterator.next();
                client.player.setYaw(newYaw);

                delayTicks = 2; // wait 2 ticks before next screenshot
                shotIndex++;

            } else {
                // Done capturing
                client.player.setYaw(originalYaw);
                capturing = false;
                yawIterator = null;
                shotIndex = 0;
                client.player.sendMessage(Text.literal("Captured 360° screenshots!"), false);
            }
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
        delayTicks = 2; // initial delay before first screenshot
        shotIndex = 0;

        client.player.sendMessage(Text.literal("Starting 360° capture..."), false);
    }

    private void takeScreenshot(MinecraftClient client) {
        String filename = String.format("360_%d_%03d.png",
                System.currentTimeMillis(), shotIndex);

        ScreenshotRecorder.saveScreenshot(
                folder,
                filename,
                client.getFramebuffer(),
                1,   // FIXED: scale factor must NOT be zero
                text -> {}
        );
    }
}
