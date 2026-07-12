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
    private static KeyMapping toggleModeKey;
    private static KeyMapping.Category miscCategory;

    private Cam360Config config;

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

    /**
     * Inline config to avoid missing-symbol compile errors.
     * Replace with your full config system later.
     */
    private static final class Cam360Config {
        CaptureMode captureMode = CaptureMode.SEPARATE;

        static Cam360Config load(File gameDir) {
            return new Cam360Config();
        }

        void save(File gameDir) {
            // no-op for now
        }
    }

    private enum CaptureMode {
        SEPARATE,
        AUTO_STITCH;

        CaptureMode next() {
            return this == SEPARATE ? AUTO_STITCH : SEPARATE;
        }
    }

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
        Minecraft mc = Minecraft.getInstance();
        config = Cam360Config.load(mc.gameDirectory);

        miscCategory = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("cam360", "misc")
        );

        captureKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cam360.capture",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                miscCategory
        ));

        toggleModeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cam360.toggle_mode",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F11,
                miscCategory
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;

            while (toggleModeKey.consumeClick()) {
                config.captureMode = config.captureMode.next();
                config.save(client.gameDirectory);
                client.player.sendSystemMessage(Component.literal("Cam360 mode: " + config.captureMode));
            }

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

        File[] existing = outDir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (existing != null) {
            for (File f : existing) knownPngPaths.add(f.getAbsolutePath());
        }

        List<ViewStep> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            steps.add(new ViewStep(originalYaw + (i * 45.0f), originalPitch));
        }
        steps.add(new ViewStep(originalYaw, -90.0f));
        steps.add(new ViewStep(originalYaw, 90.0f));
        stepIterator = steps.iterator();

        client.player.sendSystemMessage(Component.literal(
                "Capturing panorama frames... Mode: " + config.captureMode +
                        " | Output: " + outDir.getAbsolutePath()
        ));
    }

    private void pollForNewScreenshot(Minecraft client) {
        File newest = findNewestNewPng(client);

        if (newest != null) {
            knownPngPaths.add(newest.getAbsolutePath());
            capturedShots.add(newest);

            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("Cam360 saved: " + newest.getName()));
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
                client.player.sendSystemMessage(Component.literal("Cam360 warning: screenshot file not detected in time."));
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

            if (config.captureMode == CaptureMode.AUTO_STITCH) {
                if (capturedShots.size() < 10) {
                    client.player.sendSystemMessage(Component.literal(
                            "Auto-stitch skipped: only " + capturedShots.size() + "/10 frames captured."
                    ));
                } else {
                    try {
                        File panoDir = new File(client.gameDirectory, "screenshots360/360_panoramas");
                        File stitched = PanoramaStitcher.stitchSimple(capturedShots, panoDir, System.currentTimeMillis());
                        client.player.sendSystemMessage(Component.literal("Panorama stitched: " + stitched.getAbsolutePath()));
                    } catch (Exception e) {
                        client.player.sendSystemMessage(Component.literal(
                                "Auto-stitch failed (" + e.getClass().getSimpleName() + "). Separate shots kept."
                        ));
                    }
                }
            } else {
                client.player.sendSystemMessage(Component.literal(
                        "360° Panorama completed. Saved " + capturedShots.size() + " shots to: " +
                                getCustomScreenshotDir(client).getAbsolutePath()
                ));
            }
        }
    }

    private void triggerPanoramixScreenshot(Minecraft client) {
        try {
            Minecraft instance = Minecraft.getInstance();
            if (instance == null) return;

            File outDir = getCustomScreenshotDir(client);
            if (!outDir.exists()) outDir.mkdirs();

            // confirmed signature from your CI log:
            // grabPanoramixScreenshot(File)
            instance.grabPanoramixScreenshot(outDir);
        } catch (Throwable t) {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "Cam360 screenshot error: " + t.getClass().getSimpleName()
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
            String p = f.getAbsolutePath();
            if (knownPngPaths.contains(p)) continue;

            long lm = f.lastModified();
            if (lm > newestTs) {
                newestTs = lm;
                newest = f;
            }
        }
        return newest;
    }

    private File getCustomScreenshotDir(Minecraft client) {
        return new File(client.gameDirectory, "screenshots360/screenshots/screenshots");
    }
}
