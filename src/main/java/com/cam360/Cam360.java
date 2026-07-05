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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
    private long captureStartMs = 0L;

    // async screenshot pickup state
    private boolean awaitingScreenshotFile = false;
    private int awaitingIndex = -1;
    private int screenshotPollTicks = 0;

    private final List<File> capturedShots = new ArrayList<>();
    private final List<String> consumedVanillaPaths = new ArrayList<>();

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

            // If waiting for a screenshot file, poll disk first
            if (awaitingScreenshotFile) {
                pollForScreenshotAndMove(client);
                return;
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // capture previous settled view
            if (shotIndex > 0) {
                triggerVanillaScreenshot(client);
                awaitingScreenshotFile = true;
                awaitingIndex = shotIndex;
                screenshotPollTicks = 20; // increased to 1s
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
        captureStartMs = System.currentTimeMillis();

        capturedShots.clear();
        consumedVanillaPaths.clear();

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

    private void pollForScreenshotAndMove(Minecraft client) {
        File newest = findNewestUnconsumedVanillaScreenshot(client);

        if (newest != null) {
            File moved = moveToCustomDir(client, newest, awaitingIndex);
            consumedVanillaPaths.add(newest.getAbsolutePath());

            if (moved != null) {
                capturedShots.add(moved);
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("Cam360 saved: " + moved.getName()));
                }
            } else if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("Cam360 failed moving screenshot file."));
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
                client.player.sendSystemMessage(Component.literal("Cam360 warning: screenshot file not detected in time."));
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

    // FIXED: use click() instead of setDown(true/false)
    private void triggerVanillaScreenshot(Minecraft client) {
        try {
            KeyMapping screenshotKey = client.options.keyScreenshot;
            if (screenshotKey != null) {
                screenshotKey.click();
            }
        } catch (Throwable ignored) {
        }
    }

    private File findNewestUnconsumedVanillaScreenshot(Minecraft client) {
        File vanillaDir = new File(client.gameDirectory, "screenshots");
        if (!vanillaDir.exists() || !vanillaDir.isDirectory()) return null;

        File[] pngs = vanillaDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (pngs == null || pngs.length == 0) return null;

        List<File> candidates = new ArrayList<>();
        for (File f : pngs) {
            if (f.lastModified() >= captureStartMs && !consumedVanillaPaths.contains(f.getAbsolutePath())) {
                candidates.add(f);
            }
        }
        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingLong(File::lastModified).reversed());
        return candidates.get(0);
    }

    private File moveToCustomDir(Minecraft client, File source, int index) {
        File outDir = getCustomScreenshotDir(client);
        if (!outDir.exists() && !outDir.mkdirs()) return null;

        String name = String.format("cam360_%d_%03d.png", captureSessionId, index);
        File target = new File(outDir, name);

        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException moveFailed) {
            try {
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(source.toPath());
                return target;
            } catch (IOException ignored) {
                return null;
            }
        }
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
