package com.cam360;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

    public static class Cam360Config {
        public Mode captureMode = Mode.PANORAMA;
        public enum Mode {
            PANORAMA;
            public Mode next() { return PANORAMA; }
        }
        public static Cam360Config load(File dir) { return new Cam360Config(); }
        public void save(File dir) {}
    }

    public record ViewStep(float yaw, float pitch) {}

    @Override
    public void onInitializeClient() {
        Minecraft mc = Minecraft.getInstance();
        config = Cam360Config.load(mc.gameDirectory);

        // Official Mojang key mapping structure
        captureKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cam360.capture",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                "category.cam360"
        ));

        toggleModeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cam360.toggle_mode",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F11,
                "category.cam360"
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
                screenshotPollTicks = 30; 
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
            for (File f : existing) {
                knownPngPaths.add(f.getAbsolutePath());
            }
        }

        List<ViewStep> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            steps.add(new ViewStep(originalYaw + (i * 45.0f), originalPitch));
        }
        steps.add(new ViewStep(originalYaw, -90.0f));
        steps.add(new ViewStep(originalYaw, 90.0f));
        stepIterator = steps.iterator();

        client.player.sendSystemMessage(Component.literal(
                "Capturing panorama frames... Target: " + outDir.getAbsolutePath()
        ));
    }

    private void pollForNewScreenshot(Minecraft client) {
        File newest = findNewestNewPng(client);
        if (newest != null) {
            knownPngPaths.add(newest.getAbsolutePath());
            capturedShots.add(newest);
            awaitingScreenshotFile = false;
            rotateToNextStepOrFinish(client);
        } else {
            screenshotPollTicks--;
            if (screenshotPollTicks <= 0) {
                awaitingScreenshotFile = false;
                rotateToNextStepOrFinish(client);
            }
        }
    }

    private void rotateToNextStepOrFinish(Minecraft client) {
        if (client.player == null) return;

        if (stepIterator.hasNext()) {
            ViewStep next = stepIterator.next();
            client.player.setYRot(next.yaw());
            client.player.setXRot(next.pitch());
            shotIndex++;
            delayTicks = 3; // Accommodate rendering settle frames for Vulkan pipelines
        } else {
            client.player.setYRot(originalYaw);
            client.player.setXRot(originalPitch);
            capturing = false;
            client.player.sendSystemMessage(Component.literal("Panorama complete! Look in screenshots360/screenshots/"));
        }
    }

    private void triggerPanoramixScreenshot(Minecraft client) {
        // Direct Mojang official target override passing the 360 subfolder base instead of gameDirectory
        Screenshot.grab(
                getCustomScreenshotDir(client).getParentFile().getParentFile(), // Trims nested dirs to let grab() append /screenshots/ naturally
                client.getMainRenderTarget(),
                component -> { if (client.player != null) client.player.sendSystemMessage(component); }
        );
    }

    // UPDATED: Points to <minecraft_dir>/screenshots360/screenshots/
    private File getCustomScreenshotDir(Minecraft client) {
        File base360 = new File(client.gameDirectory, "screenshots360");
        return new File(base360, "screenshots");
    }

    private File findNewestNewPng(Minecraft client) {
        File outDir = getCustomScreenshotDir(client);
        File[] files = outDir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files == null) return null;

        File newest = null;
        long lastMod = Long.MIN_VALUE;

        for (File f : files) {
            if (!knownPngPaths.contains(f.getAbsolutePath()) && f.lastModified() > lastMod) {
                newest = f;
                lastMod = f.lastModified();
            }
        }
        return newest;
    }
}
