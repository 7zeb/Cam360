package com.cam360;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.glfw.GLFW;

import java.io.File;

public class Cam360 implements ModInitializer {
    private static KeyBinding captureKey;

    @Override
    public void onInitialize() {
        captureKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.360cam.capture",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            "category.360cam"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (captureKey.wasPressed()) {
                capture360(client);
            }
        });
    }

    private void capture360(MinecraftClient client) {
        if (client.player == null) return;

        float originalYaw = client.player.getYaw();
        File folder = new File(client.runDirectory, "screenshots360");
        if (!folder.exists()) folder.mkdirs();

        for (int i = 0; i < 8; i++) { // 8 shots = 45° increments
            float newYaw = originalYaw + (i * 45);
            client.player.setYaw(newYaw);

            String filename = "360_" + System.currentTimeMillis() + "_" + i + ".png";
            ScreenshotRecorder.saveScreenshot(folder, filename, client.getFramebuffer());
        }

        client.player.setYaw(originalYaw);
    }
}
