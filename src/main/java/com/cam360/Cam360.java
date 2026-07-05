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

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            if (shotIndex > 0) {
                File f = takeScreenshotViaKeybind(client, shotIndex);
                if (f != null) capturedShots.add(f);
            }

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
        });
    }

    private void startCapture(Minecraft client) {
        if (capturing || client.player == null) return;

        originalYaw = client.player.getYRot();
        originalPitch = client.player.getXRot();
        captureSessionId = System.currentTimeMillis();

        capturedShots.clear();

        capturing = true;
        delayTicks = 4;
        shotIndex = 0;

        List<ViewStep> steps = new ArrayList<>();
        int yawSteps = 8;
        for (int i = 0; i < yawSteps; i++) {
            steps.add(new ViewStep(originalYaw + (i * 45.0f), originalPitch));
        }
        steps.add(new ViewStep(originalYaw, -90.0f));
        steps.add(new ViewStep(originalYaw, 90.0f));
        stepIterator = steps.iterator();

        client.player.sendSystemMessage(Component.literal("Capturing panorama frames... Mode: " + config.captureMode));
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

        if (config.captureMode == CaptureMode.AUTO_STITCH) {
            try {
                File outDir = new File(client.gameDirectory, "screenshots/360_panoramas");
                File stitched = PanoramaStitcher.stitchSimple(capturedShots, outDir, captureSessionId);
                client.player.sendSystemMessage(Component.literal("Panorama stitched: " + stitched.getName()));
            } catch (Exception e) {
                client.player.sendSystemMessage(Component.literal("Auto-stitch failed, kept separate screenshots."));
            }
        } else {
            client.player.sendSystemMessage(Component.literal("360° Panorama completed (separate screenshots)."));
        }
    }

    /**
     * Safer backend path: uses vanilla screenshot key path.
     * Note: vanilla chooses filename/path. We create an expected File reference for stitch input.
     */
    private File takeScreenshotViaKeybind(Minecraft client, int index) {
        try {
            String base = "cam360_" + captureSessionId + "_" + String.format("%03d", index);
            File screenshotsDir = new File(client.gameDirectory, "screenshots");
            if (!screenshotsDir.exists()) screenshotsDir.mkdirs();

            // Trigger screenshot
            client.options.keyScreenshot.setDown(true);
            client.execute(() -> client.options.keyScreenshot.setDown(false));

            // We cannot guarantee vanilla exact naming here, so we return an expected path placeholder.
            // For production, replace with a direct screenshot API call once your mappings are locked.
            return new File(screenshotsDir, base + ".png");
        } catch (Throwable t) {
            return null;
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
}
