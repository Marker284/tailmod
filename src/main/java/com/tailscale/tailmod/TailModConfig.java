package com.tailscale.tailmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TailModConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("tailmod");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String authKey = "";
    private List<Integer> udpPorts = new ArrayList<>(List.of(24454, 25454));
    private Set<String> favorites = new LinkedHashSet<>();

    public String getAuthKey() { return authKey; }
    public void setAuthKey(String key) { authKey = key != null ? key : ""; }

    public List<Integer> getUdpPorts() { return udpPorts; }
    public void setUdpPorts(List<Integer> ports) { udpPorts = ports != null ? ports : new ArrayList<>(); }

    public boolean isFavorite(String hostname) { return favorites != null && favorites.contains(hostname); }
    public void toggleFavorite(String hostname) {
        if (favorites == null) favorites = new LinkedHashSet<>();
        if (!favorites.remove(hostname)) favorites.add(hostname);
        save();
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("tailmod").resolve("config.json");
    }

    public static TailModConfig load() {
        Path file = configFile();
        try {
            if (Files.exists(file)) {
                TailModConfig cfg = GSON.fromJson(Files.readString(file), TailModConfig.class);
                if (cfg != null) {
                    if (cfg.udpPorts  == null) cfg.udpPorts  = new ArrayList<>(List.of(24454, 25454));
                    if (cfg.favorites == null) cfg.favorites = new LinkedHashSet<>();
                    return cfg;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[tailmod] Failed to load config", e);
        }
        return new TailModConfig();
    }

    public void save() {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            LOGGER.warn("[tailmod] Failed to save config", e);
        }
    }
}
