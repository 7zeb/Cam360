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

    private Iterator<ViewStep> stepIterator;
    private float originalYaw;
    private float originalPitch;

    private File folder;
    private int shotIndex = 0;

    @Override
    public void onInitializeClient() {

        // FIX: Use a plain string for the category name. 
        // "key.categories.misc" is the standard Minecraft translation key for 'Miscellaneous'.
        captureKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cam360.capture",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                "key.categories.misc"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (captureKey.wasPressed()) {
                startCapture(client);
            }

            if (!capturing || stepIterator == null) return;

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
            if (stepIterator.hasNext()) {
                ViewStep step = stepIterator.next();
                client.player.setYaw(step.yaw);
                client.player.setPitch(step.pitch);

                delayTicks = 2; // wait 2 ticks before next screenshot
                shotIndex++;

            } else {
                // Done capturing
                client.player.setYaw(originalYaw);
                client.player.setPitch(originalPitch);

                capturing = false;
                stepIterator = null;
                shotIndex = 0;
                client.player.sendMessage(Text.literal("Captured 360° screenshots + up/down!"), false);
            }
        });

        System.out.println("[Cam360] Client-side mod initialized!");
    }

    private void startCapture(MinecraftClient client) {
        if (capturing || client.player == null) return;

        originalYaw = client.player.getYaw();
        originalPitch = client.player.getPitch();

        folder = new File(client.runDirectory, "screenshots360/screenshots");
        if (!folder.exists()) folder.mkdirs();

        // Total: 8 (yaw) + 2 (up/down) = 10 screenshots
        List<ViewStep> steps = new ArrayList<>();

        int yawSteps = 8;
        for (int i = 0; i < yawSteps; i++) {
            steps.add(new ViewStep(originalYaw + (i * 45.0f), originalPitch));
        }

        // Add 2 extra screenshots: straight up + straight down (keep yaw as originalYaw)
        steps.add(new ViewStep(originalYaw, -90.0f)); // up (sky)
        steps.add(new ViewStep(originalYaw, 90.0f));  // down (ground)

        stepIterator = steps.iterator();
        capturing = true;
        delayTicks = 2; // initial delay before first screenshot
        shotIndex = 0;

        client.player.sendMessage(Text.literal("Starting 360° capture (+ up/down)..."), false);
    }

    private void takeScreenshot(MinecraftClient client) {
        String filename = String.format("360_%d_%03d.png",
                System.currentTimeMillis(), shotIndex);

        // FIX: Re-added the integer '1' for the unit/scale factor to match 
        // the 5-argument method signature required in 1.21.11.
        ScreenshotRecorder.saveScreenshot(
                folder,
                filename,
                client.getFramebuffer(),
                1,
                text -> {}
        );
    }

    private static final class ViewStep {
        final float yaw;
        final float pitch;

        private ViewStep(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
