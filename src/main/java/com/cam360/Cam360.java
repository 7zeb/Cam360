package com.cam360;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Cam360 implements ClientModInitializer {

    private static KeyMapping captureKey;

    private boolean capturing = false;
    private int delayTicks = 0; 

    private Iterator<ViewStep> stepIterator;
    private float originalYaw;
    private float originalPitch;

    private File folder;
    private int shotIndex = 0;

    @Override
    public void onInitializeClient() {

        captureKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.cam360.capture",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                "key.categories.misc"
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

        System.out.println("[Cam360] Client-side mod initialized for 26.2!");
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

        Screenshot.grab(
                folder,
                filename,
                client.getMainRenderTarget(),
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
