package com.cam360;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation; // 26.2 official mappings use ResourceLocation or Identifier
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Cam360 implements ClientModInitializer {
    private static KeyMapping captureKey;
    private static KeyMapping.Category miscCategory;

    private boolean capturing = false;
    private int delayTicks = 0;
    private Iterator<ViewStep> stepIterator;
    private float originalYaw;
    private float originalPitch;
    private int shotIndex = 0;

    private boolean awaitingScreenshotFile = false;
    private int screenshotPollTicks = 0;

    private final List<File> capturedShots = new ArrayList<>();
    private final Set<String> knownPngPaths = new HashSet<>();

    private static final class ViewStep {
        final float yaw;
        final float pitch;

        private ViewStep(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    @Override
    public void onInitializeClient() {
        // Register key category and mapping for MC 26.2
        miscCategory = KeyMapping.Category.register(
                ResourceLocation.fromNamespaceAndPath("cam360", "misc")
        );

        captureKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cam360.capture",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                miscCategory
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;

            while (captureKey.consumeClick()) {
                if (!capturing) startCapture(client);
            }

            if (!capturing) return;

            // Handle asynchronous file-saving wait time
            if (awaitingScreenshotFile) {
                pollForNewScreenshot(client);
                return;
            }

            // Let the camera view finish settling before doing anything
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // Once delayTicks hits 0, trigger a full screenshot for the CURRENT step position
            if (stepIterator != null) {
                triggerFullResScreenshot(client);
                awaitingScreenshotFile = true;
                screenshotPollTicks = 60; // 3 seconds timeout
            }
        });
    }

    private void startCapture(Minecraft client) {
        if (client.player == null) return;

        capturedShots.clear();
        knownPngPaths.clear();
        awaitingScreenshotFile = false;
        screenshotPollTicks = 0;
        shotIndex = 0;

        originalYaw = client.player.getYRot();
        originalPitch = client.player.getXRot();

        File outDir = getCustomScreenshotDir(client);
        if (!outDir.exists()) outDir.mkdirs();

        File[] existing = outDir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (existing != null) {
            for (File f : existing) knownPngPaths.add(f.getAbsolutePath());
        }

        // Build the 10 structural viewpoints (8 horizontal splits + 1 up + 1 down)
        List<ViewStep> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            steps.add(new ViewStep(originalYaw + (i * 45.0f), originalPitch));
        }
        steps.add(new ViewStep(originalYaw, -90.0f)); // Looking straight up
        steps.add(new ViewStep(originalYaw, 90.0f));  // Looking straight down
        stepIterator = steps.iterator();

        capturing = true;
        shotIndex = 0;

        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "Starting 360 capture... Output: " + outDir.getAbsolutePath()
            ));
        }

        // Snap to the very first camera position immediately
        rotateToNextStepOrFinish(client);
    }

    private void pollForNewScreenshot(Minecraft client) {
        File newest = findNewestNewPng(client);

        if (newest != null) {
            knownPngPaths.add(newest.getAbsolutePath());
            capturedShots.add(newest);

            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("Saved angle " + shotIndex + "/10: " + newest.getName()));
            }

            awaitingScreenshotFile = false;
            screenshotPollTicks = 0;
            rotateToNextStepOrFinish(client);
            return;
        }

        screenshotPollTicks--;
        if (screenshotPollTicks <= 0) {
            awaitingScreenshotFile = false;
            screenshotPollTicks = 0;

            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("Warning: Angle " + shotIndex + " screenshot save timeout."));
            }

            rotateToNextStepOrFinish(client);
        }
    }

    private void rotateToNextStepOrFinish(Minecraft client) {
        if (client.player == null) return;

        if (stepIterator != null && stepIterator.hasNext()) {
            ViewStep step = stepIterator.next();
            client.player.setYRot(step.yaw);
            client.player.setXRot(step.pitch);

            shotIndex++;
            delayTicks = 4; // Crucial: Give chunk rendering and camera positioning 4 ticks to settle
        } else {
            finishCapture(client);
        }
    }

    private void finishCapture(Minecraft client) {
        if (client.player != null) {
            client.player.setYRot(originalYaw);
            client.player.setXRot(originalPitch);

            client.player.sendSystemMessage(Component.literal(
                    "360 Capture complete! Saved " + capturedShots.size() + " files to: " +
                            getCustomScreenshotDir(client).getAbsolutePath()
            ));
        }

        capturing = false;
        delayTicks = 0;
        stepIterator = null;
        shotIndex = 0;
        awaitingScreenshotFile = false;
        screenshotPollTicks = 0;
        knownPngPaths.clear();
    }

    private void triggerFullResScreenshot(Minecraft client) {
        try {
            File outDir = getCustomScreenshotDir(client);
            if (!outDir.exists()) outDir.mkdirs();

            Screenshot.grab(
                    outDir,
                    client.getMainRenderTarget(),
                    msg -> {
                        // Nullified to prevent spamming the native vanilla chat message 10 times
                    }
            );
        } catch (Throwable t) {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "Screenshot error: " + t.getClass().getSimpleName()
                ));
            }
        }
    }

    private File findNewestNewPng(Minecraft client) {
        File dir = getCustomScreenshotDir(client);
        if (!dir.exists() || !dir.isDirectory()) return null;

        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files == null || files.length == 0) return null;

        File newest = null;
        long newestTs = Long.MIN_VALUE;

        for (File f : files) {
            String path = f.getAbsolutePath();
            if (knownPngPaths.contains(path)) continue;

            long lm = f.lastModified();
            if (lm > newestTs) {
                newestTs = lm;
                newest = f;
            }
        }
        return newest;
    }

    private File getCustomScreenshotDir(Minecraft client) {
        return new File(client.gameDirectory, "screenshots360");
    }
}
