package com.cam360;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
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
    private static KeyMapping.Category miscCategory;

    private boolean capturing = false;
    private int delayTicks = 0;
    private Iterator<ViewStep> stepIterator;
    private float originalYaw;
    private float originalPitch;
    private File folder;
    private int shotIndex = 0;
    private long captureSessionId = 0L;

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

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            if (shotIndex > 0) {
                takeScreenshot();
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
                client.player.sendSystemMessage(Component.literal("360° Panorama completed."));
            }
        });
    }

    private void startCapture(Minecraft client) {
        if (capturing || client.player == null) return;

        originalYaw = client.player.getYRot();
        originalPitch = client.player.getXRot();
        captureSessionId = System.currentTimeMillis();

        folder = new File(client.gameDirectory, "screenshots/360_panoramas");
        if (!folder.exists() && !folder.mkdirs()) {
            client.player.sendSystemMessage(Component.literal("Cam360: Failed to create screenshot folder."));
            return;
        }

        List<ViewStep> steps = new ArrayList<>();
        int yawSteps = 8;
        for (int i = 0; i < yawSteps; i++) {
            steps.add(new ViewStep(originalYaw + (i * 45.0f), originalPitch));
        }
        steps.add(new ViewStep(originalYaw, -90.0f));
        steps.add(new ViewStep(originalYaw, 90.0f));

        stepIterator = steps.iterator();
        capturing = true;
        delayTicks = 4;
        shotIndex = 0;

        client.player.sendSystemMessage(Component.literal("Capturing panorama frames..."));
    }

    private void takeScreenshot() {
        Minecraft instance = Minecraft.getInstance();
        if (instance == null || this.folder == null) return;

        String filename = String.format("360_%d_%03d.png", captureSessionId, shotIndex);

        Screenshot.grab(
            this.folder,
            filename,
            instance.getMainRenderTarget(),
            message -> instance.execute(() -> {})
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
