package com.cam360;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
        miscCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("cam360", "misc")
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
                startCapture(client);
            }

            if (!capturing || stepIterator == null) return;

            if (awaitingScreenshotFile) {
                pollForNewScreenshot(client);
                return;
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // Capture previous settled view
            if (shotIndex > 0) {
                triggerPanoramixScreenshot(client);
                awaitingScreenshotFile = true;
                screenshotPollTicks = 40; // up to ~2s
                return;
            }

            rotateToNextStepOrFinish(client);
        });
    }

    private void startCapture(Minecraft client) {
        if (capturing || client.player == null) return;

        originalYaw = client.player.getYRot();
        originalPitch = client.player.getXRot();

        capturedShots.clear();
        knownPngPaths.clear();
        awaitingScreenshotFile = false;
        screenshotPollTicks = 0;

        capturing = true;
        delayTicks = 4;
        shotIndex = 0;

        File outDir = getCustomScreenshotDir(client);
        if (!outDir.exists()) outDir.mkdirs();

        // Snapshot existing PNGs so we only count new files
        File[] existing = outDir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (existing != null) {
            for (File f : existing) knownPngPaths.add(f.getAbsolutePath());
        }

        List<ViewStep> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            steps.add(new ViewStep(originalYaw + (i * 45.0f), originalPitch));
        }
        steps.add(new ViewStep(originalYaw, -90.0f)); // up
        steps.add(new ViewStep(originalYaw, 90.0f));  // down
        stepIterator = steps.iterator();

        client.player.sendSystemMessage(Component.literal(
                "Starting 360 capture... Output: " + outDir.getAbsolutePath()
        ));
    }

    private void pollForNewScreenshot(Minecraft client) {
        File newest = findNewestNewPng(client);

        if (newest != null) {
            knownPngPaths.add(newest.getAbsolutePath());
            capturedShots.add(newest);

            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("Saved: " + newest.getName()));
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
                client.player.sendSystemMessage(Component.literal("Warning: screenshot file not detected in time."));
            }

            rotateToNextStepOrFinish(client);
        }
    }

    private void rotateToNextStepOrFinish(Minecraft client) {
        if (client.player == null) return;

        if (stepIterator.hasNext()) {
            ViewStep step = stepIterator.next();
            client.player.setYRot(step.yaw);
            client.player.setXRot(step.pitch);

            delayTicks = 3;
            shotIndex++;
        } else {
            client.player.setYRot(originalYaw);
            client.player.setXRot(originalPitch);

            capturing = false;
            stepIterator = null;
            shotIndex = 0;
            awaitingScreenshotFile = false;
            screenshotPollTicks = 0;

            client.player.sendSystemMessage(Component.literal(
                    "Captured 360° screenshots + up/down! Saved " + capturedShots.size() +
                            " shots to: " + getCustomScreenshotDir(client).getAbsolutePath()
            ));
        }
    }

    private void triggerPanoramixScreenshot(Minecraft client) {
        try {
            Minecraft instance = Minecraft.getInstance();
            if (instance == null) return;

            File outDir = getCustomScreenshotDir(client);
            if (!outDir.exists()) outDir.mkdirs();

            // Confirmed signature in your environment:
            instance.grabPanoramixScreenshot(outDir);
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
        // your requested path
        return new File(client.gameDirectory, "screenshots360/screenshots/screenshots");
    }
}
