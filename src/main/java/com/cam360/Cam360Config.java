package com.cam360;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public final class Cam360Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public CaptureMode captureMode = CaptureMode.SEPARATE;

    public static Cam360Config load(File gameDir) {
        File configDir = new File(gameDir, "config");
        if (!configDir.exists()) configDir.mkdirs();

        File file = new File(configDir, "cam360.json");
        if (!file.exists()) {
            Cam360Config cfg = new Cam360Config();
            cfg.save(gameDir);
            return cfg;
        }

        try (FileReader reader = new FileReader(file)) {
            Cam360Config cfg = GSON.fromJson(reader, Cam360Config.class);
            return cfg == null ? new Cam360Config() : cfg;
        } catch (Exception e) {
            return new Cam360Config();
        }
    }

    public void save(File gameDir) {
        File configDir = new File(gameDir, "config");
        if (!configDir.exists()) configDir.mkdirs();

        File file = new File(configDir, "cam360.json");
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(this, writer);
        } catch (IOException ignored) {
        }
    }
}
