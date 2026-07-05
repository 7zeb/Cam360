package com.cam360;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

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
    private long captureSessionId = 0L;

    // async screenshot pickup state
    private boolean awaitingScreenshotFile = false;
    private int awaitingIndex = -1;
    private int screenshotPollTicks = 0;

    private final List<File> capturedShots = new ArrayList<>();

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

            // Wait for file after screenshot request
            if (awaitingScreenshotFile) {
                pollForExpectedScreenshot(client);
                return;
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // Capture previous settled view
            if (shotIndex > 0) {
                awaitingIndex = shotIndex;
                triggerPanoramixScreenshot(client);
                awaitingScreenshotFile = true;
                screenshotPollTicks = 20; // ~1s
                return;
            }

            rotateToNextStepOrFinish(client);
        });
    }

    private void startCapture(Minecraft client) {
        if (capturing || client.player == null) return;

        originalYaw = client.player.getYRot();
        originalPitch = client.player.getXRot();
        captureSessionId = System.currentTimeMillis();

        capturedShots.clear();

        awaitingScreenshotFile = false;
        awaitingIndex = -1;
        screenshotPollTicks = 0;

        capturing = true;
        delayTicks = 4;
        shotIndex = 0;

        List<ViewStep> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            steps.add(new ViewStep(originalYaw + (i * 45.0f), originalPitch));
        }
        steps.add(new ViewStep(originalYaw, -90.0f));
        steps.add(new ViewStep(originalYaw, 90.0f));
        stepIterator = steps.iterator();

        File outDir = getCustomScreenshotDir(client);
        if (!outDir.exists()) outDir.mkdirs();

        client.player.sendSystemMessage(Component.literal(
                "Capturing panorama frames... Mode: " + config.captureMode + " | Output: " + outDir.getAbsolutePath()
        ));
    }

    private void pollForExpectedScreenshot(Minecraft client) {
        File expected = getExpectedShotFile(client, awaitingIndex);

        if (expected.exists() && expected.isFile()) {
            capturedShots.add(expected);

            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("Cam360 saved: " + expected.getName()));
            }

            awaitingScreenshotFile = false;
            awaitingIndex = -1;
            screenshotPollTicks = 0;

            rotateToNextStepOrFinish(client);
            return;
        }

        // fallback: pick newest file matching session prefix (handles MC auto-suffixes)
        File fallback = findNewestMatchingSessionFile(client);
        if (fallback != null && !capturedShots.contains(fallback)) {
            capturedShots.add(fallback);

            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("Cam360 saved: " + fallback.getName()));
            }

            awaitingScreenshotFile = false;
            awaitingIndex = -1;
            screenshotPollTicks = 0;

            rotateToNextStepOrFinish(client);
            return;
        }

        screenshotPollTicks--;
        if (screenshotPollTicks <= 0) {
            awaitingScreenshotFile = false;
            awaitingIndex = -1;
            screenshotPollTicks = 0;

            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "Cam360 warning: screenshot file not detected in time."
                ));
            }

            rotateToNextStepOrFinish(client);
        }
    }

    private void rotateToNextStepOrFinish(Minecraft client) {
        if (stepIterator.hasNext()) {
            ViewStep step = stepIterator.next();

            client.player.setYRot(step.yaw);
            client.player.setXRot(step.pitch);
            client.player.yRotO = step.yaw;
            client.player.xRotO = step.pitch;
            client.player.yHeadRot = step.yaw;
            client.player.yHeadRotO = step.yaw;

            if (client.getConnection() != null) {
                client.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                        step.yaw, step.pitch, client.player.onGround(), false
                ));
            }

            delayTicks = 4;
            shotIndex++;
        } else {
            endCapture(client);
        }
    }

    private void endCapture(Minecraft client) {
        if (client.player == null) return;

        client.player.setYRot(originalYaw);
        client.player.setXRot(originalPitch);
        client.player.yRotO = originalYaw;
        client.player.xRotO = originalPitch;
        client.player.yHeadRot = originalYaw;
        client.player.yHeadRotO = originalYaw;

        if (client.getConnection() != null) {
            client.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                    originalYaw, originalPitch, client.player.onGround(), false
            ));
        }

        capturing = false;
        stepIterator = null;
        shotIndex = 0;
        awaitingScreenshotFile = false;
        awaitingIndex = -1;
        screenshotPollTicks = 0;

        if (config.captureMode == CaptureMode.AUTO_STITCH) {
            if (capturedShots.size() < 10) {
                client.player.sendSystemMessage(Component.literal(
                        "Auto-stitch skipped: only " + capturedShots.size() + "/10 frames captured."
                ));
                client.player.sendSystemMessage(Component.literal(
                        "Frames saved to: " + getCustomScreenshotDir(client).getAbsolutePath()
                ));
                return;
            }

            try {
                // Keep shot order stable
                capturedShots.sort(Comparator.comparing(File::getName));

                File panoDir = new File(client.gameDirectory, "screenshots360/360_panoramas");
                File stitched = PanoramaStitcher.stitchSimple(capturedShots, panoDir, captureSessionId);
                client.player.sendSystemMessage(Component.literal("Panorama stitched: " + stitched.getAbsolutePath()));
            } catch (Exception e) {
                client.player.sendSystemMessage(Component.literal(
                        "Auto-stitch failed (" + e.getClass().getSimpleName() + "). Separate shots kept."
                ));
            }
        } else {
            client.player.sendSystemMessage(Component.literal(
                    "360° Panorama completed. Saved " + capturedShots.size() + " shots to: "
                            + getCustomScreenshotDir(client).getAbsolutePath()
            ));
        }
    }

    private void triggerPanoramixScreenshot(Minecraft client) {
        try {
            Minecraft instance = Minecraft.getInstance();
            if (instance == null) return;

            File outDir = getCustomScreenshotDir(client);
            if (!outDir.exists()) outDir.mkdirs();

            String baseName = String.format("cam360_%d_%03d", captureSessionId, awaitingIndex);
            instance.grabPanoramixScreenshot(outDir, baseName);
        } catch (Throwable t) {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "Cam360 screenshot error: " + t.getClass().getSimpleName()
                ));
            }
        }
    }

    private File getExpectedShotFile(Minecraft client, int index) {
        return new File(getCustomScreenshotDir(client),
                String.format("cam360_%d_%03d.png", captureSessionId, index));
    }

    private File findNewestMatchingSessionFile(Minecraft client) {
        File dir = getCustomScreenshotDir(client);
        if (!dir.exists() || !dir.isDirectory()) return null;

        String prefix = "cam360_" + captureSessionId + "_";
        File[] files = dir.listFiles((d, name) ->
                name.toLowerCase().endsWith(".png") && name.startsWith(prefix));

        if (files == null || files.length == 0) return null;

        File newest = files[0];
        for (File f : files) {
            if (f.lastModified() > newest.lastModified()) newest = f;
        }
        return newest;
    }

    private File getCustomScreenshotDir(Minecraft client) {
        return new File(client.gameDirectory, "screenshots360/screenshots/screenshots");
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
