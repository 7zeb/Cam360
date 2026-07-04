package com.cam360;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier; 
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Cam360 implements ClientModInitializer {

    private static KeyMapping captureKey;

    private static final KeyMapping.Category MISC_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("cam360", "misc")
    );

    private boolean capturing = false;
    private int delayTicks = 0; 

    private Iterator<ViewStep> stepIterator;
    private float originalYaw;
    private float originalPitch;

    private File folder;
    private int shotIndex = 0;

    @Override
    public void onInitializeClient() {

        captureKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cam360.capture",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                MISC_CATEGORY
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

            if (delayTicks == 0 && shotIndex > 0) {
                takeScreenshot(client);
            }

            if (stepIterator.hasNext()) {
                ViewStep step = stepIterator.next();
                client.player.setYRot(step.yaw);
                client.player.setXRot(step.pitch);

                delayTicks = 2; 
                shotIndex++;

            } else {
                client.player.setYRot(originalYaw);
                client.player.setXRot(originalPitch);

                capturing = false;
                stepIterator = null;
                shotIndex = 0;
                client.player.sendSystemMessage(Component.literal("Captured 360° screenshots + up/down!"));
            }
        });

        System.out.println("[Cam360] Cam360 is ready... waiting for keybinds for screenshots");
    }

    private void startCapture(Minecraft client) {
        if (capturing || client.player == null) return;

        originalYaw = client.player.getYRot();
        originalPitch = client.player.getXRot();

        folder = new File(client.gameDirectory, "screenshots360/screenshots");
        if (!folder.exists()) folder.mkdirs();

        List<ViewStep> steps = new ArrayList<>();

        int yawSteps = 8;
        for (int i = 0; i < yawSteps; i++) {
            steps.add(new ViewStep(originalYaw + (i * 45.0f), originalPitch));
        }

        steps.add(new ViewStep(originalYaw, -90.0f)); 
        steps.add(new ViewStep(originalYaw, 90.0f));  

        stepIterator = steps.iterator();
        capturing = true;
        delayTicks = 2;
        shotIndex = 0;

        client.player.sendSystemMessage(Component.literal("Starting 360° capture (+ up/down)..."));
    }

    private void takeScreenshot(Minecraft client) {
        String filename = String.format("360_%d_%03d.png",
                System.currentTimeMillis(), shotIndex);

        // FIXED: Reverted to the precise public getter method required to pipe target buffer assets
        Screenshot.grab(
                folder,
                filename,
                client.getRenderTarget(),
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
