package com.digup.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AppSettings {
    public String hotkey = "Alt+Space";
    public List<String> roots = new ArrayList<>();
    public List<String> excludedFolders = new ArrayList<>();
    public List<String> excludedTypes = new ArrayList<>();
    public List<String> codeRoots = new ArrayList<>();
    public String codeEditor = "vscode";
    public boolean indexOnBattery = true;
    public boolean launchAtLogin = false;
    public boolean onboarded = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path getAppDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) {
            appData = System.getProperty("user.home") + "\\AppData\\Roaming";
        }
        return Path.of(appData, "DigUp");
    }

    public static Path getSettingsFile() {
        return getAppDataDir().resolve("settings.json");
    }

    public static AppSettings load() {
        Path path = getSettingsFile();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                AppSettings s = GSON.fromJson(reader, AppSettings.class);
                if (s != null) return s;
            } catch (Exception ignored) {}
        }
        return new AppSettings();
    }

    public void save() {
        try {
            Path dir = getAppDataDir();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            try (FileWriter writer = new FileWriter(getSettingsFile().toFile())) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
