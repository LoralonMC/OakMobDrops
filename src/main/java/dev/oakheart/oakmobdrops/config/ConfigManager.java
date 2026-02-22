package dev.oakheart.oakmobdrops.config;

import dev.oakheart.oakmobdrops.OakMobDrops;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public class ConfigManager {

    private final OakMobDrops plugin;
    private final Logger logger;
    private final File configFile;
    private FileConfiguration config;

    // Cached config values - settings
    private boolean debug;
    private boolean useLootingEnchant;
    private double lootingMultiplier;
    private boolean globalAllowSpawnerDrops;
    private boolean inheritSpawnerTag;
    private double searchRadius;

    // Sound settings
    private Sound dropSound;
    private boolean playSound;

    // Announcement
    private String globalAnnouncement;

    // Statistics settings
    private boolean enableStatistics;
    private int autoSaveInterval;
    private int maxPlayerStats;

    // Storage
    private String storageType;

    public ConfigManager(OakMobDrops plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }

    public void load() {
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        mergeDefaults();

        if (!validate(config)) {
            throw new RuntimeException("Configuration validation failed");
        }

        cacheValues();
    }

    public boolean reload() {
        FileConfiguration newConfig = YamlConfiguration.loadConfiguration(configFile);

        if (!validate(newConfig)) {
            logger.warning("Configuration reload failed validation. Keeping previous configuration.");
            return false;
        }

        this.config = newConfig;
        mergeDefaults();

        cacheValues();
        logger.info("Configuration reloaded successfully.");
        return true;
    }

    private void mergeDefaults() {
        try (var stream = plugin.getResource("config.yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                config.setDefaults(defaults);

                if (hasNewKeys(defaults)) {
                    config.options().copyDefaults(true);
                    config.save(configFile);
                    logger.info("Config updated with new default values.");
                }
            }
        } catch (IOException e) {
            logger.warning("Could not save config defaults: " + e.getMessage());
        }
    }

    private boolean hasNewKeys(FileConfiguration defaults) {
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !config.contains(key, true)) {
                return true;
            }
        }
        return false;
    }

    private static final int SUPPORTED_CONFIG_VERSION = 1;

    // Validation defaults — shared between validate() and cacheValues()
    private static final double DEFAULT_LOOTING_MULTIPLIER = 0.5;
    private static final double DEFAULT_SEARCH_RADIUS = 50.0;
    private static final int DEFAULT_AUTOSAVE_INTERVAL = 5;
    private static final int DEFAULT_MAX_PLAYER_STATS = 1000;

    private boolean validate(FileConfiguration configToValidate) {
        List<String> warnings = new ArrayList<>();

        // Fatal: config from a future plugin version
        int configVersion = configToValidate.getInt("config-version", 1);
        if (configVersion > SUPPORTED_CONFIG_VERSION) {
            logger.warning("=== Configuration Error ===");
            logger.warning("  - config-version " + configVersion + " is newer than supported version " +
                    SUPPORTED_CONFIG_VERSION + ". Please update the plugin or fix config.yml.");
            logger.warning("===========================");
            return false;
        }

        double looting = configToValidate.getDouble("settings.looting-multiplier", DEFAULT_LOOTING_MULTIPLIER);
        if (looting < 0.0 || looting > 10.0) {
            warnings.add("settings.looting-multiplier must be between 0.0 and 10.0, got: " + looting + ". Using default: " + DEFAULT_LOOTING_MULTIPLIER);
        }

        double radius = configToValidate.getDouble("settings.drop-recipient-search-radius", DEFAULT_SEARCH_RADIUS);
        if (radius < 1.0 || radius > 500.0) {
            warnings.add("settings.drop-recipient-search-radius must be between 1.0 and 500.0, got: " + radius + ". Using default: " + DEFAULT_SEARCH_RADIUS);
        }

        int autosave = configToValidate.getInt("settings.statistics-autosave-interval", DEFAULT_AUTOSAVE_INTERVAL);
        if (autosave < 0 || autosave > 60) {
            warnings.add("settings.statistics-autosave-interval must be between 0 and 60, got: " + autosave + ". Using default: " + DEFAULT_AUTOSAVE_INTERVAL);
        }

        int maxStats = configToValidate.getInt("settings.max-player-stats", DEFAULT_MAX_PLAYER_STATS);
        if (maxStats != -1 && maxStats <= 0) {
            warnings.add("settings.max-player-stats must be -1 (unlimited) or > 0, got: " + maxStats + ". Using default: " + DEFAULT_MAX_PLAYER_STATS);
        }

        String rawSound = configToValidate.getString("settings.sound", "minecraft:ui.toast.challenge_complete");
        if (rawSound != null) {
            NamespacedKey soundKey = NamespacedKey.fromString(rawSound);
            if (soundKey == null || Registry.SOUNDS.get(soundKey) == null) {
                warnings.add("Unknown sound '" + rawSound + "'; using minecraft:ui.toast.challenge_complete");
            }
        }

        String storage = configToValidate.getString("settings.storage-type", "json").toLowerCase(Locale.ROOT);
        if (!"json".equals(storage) && !"sqlite".equals(storage)) {
            warnings.add("settings.storage-type must be 'json' or 'sqlite', got: '" + storage + "'. Using default: json");
        }

        // All other settings have safe defaults — no additional fatal conditions
        if (!warnings.isEmpty()) {
            logger.warning("=== Configuration Warnings ===");
            warnings.forEach(w -> logger.warning("  - " + w));
            logger.warning("==============================");
        }

        return true;
    }

    private void cacheValues() {
        debug = config.getBoolean("settings.debug", false);
        useLootingEnchant = config.getBoolean("settings.use-looting-enchantment", true);

        // Safety net — validate() already warned about out-of-range values
        lootingMultiplier = config.getDouble("settings.looting-multiplier", DEFAULT_LOOTING_MULTIPLIER);
        if (lootingMultiplier < 0.0 || lootingMultiplier > 10.0) {
            lootingMultiplier = DEFAULT_LOOTING_MULTIPLIER;
        }

        globalAllowSpawnerDrops = config.getBoolean("settings.allow-spawner-drops", false);
        inheritSpawnerTag = config.getBoolean("settings.inherit-spawner-tag", true);

        // Safety net — validate() already warned about out-of-range values
        searchRadius = config.getDouble("settings.drop-recipient-search-radius", DEFAULT_SEARCH_RADIUS);
        if (searchRadius < 1.0 || searchRadius > 500.0) {
            searchRadius = DEFAULT_SEARCH_RADIUS;
        }

        // Sound resolution
        String rawSound = config.getString("settings.sound", "minecraft:ui.toast.challenge_complete");
        NamespacedKey soundKey = (rawSound != null) ? NamespacedKey.fromString(rawSound) : null;
        dropSound = (soundKey != null) ? Registry.SOUNDS.get(soundKey) : null;
        if (dropSound == null) {
            dropSound = Registry.SOUNDS.get(NamespacedKey.minecraft("ui.toast.challenge_complete"));
        }

        playSound = config.getBoolean("settings.play-sound", true);
        globalAnnouncement = config.getString("settings.global-announcement", "");

        enableStatistics = config.getBoolean("settings.enable-statistics", true);

        // Safety net — validate() already warned about out-of-range values
        autoSaveInterval = config.getInt("settings.statistics-autosave-interval", DEFAULT_AUTOSAVE_INTERVAL);
        if (autoSaveInterval < 0 || autoSaveInterval > 60) {
            autoSaveInterval = DEFAULT_AUTOSAVE_INTERVAL;
        }

        maxPlayerStats = config.getInt("settings.max-player-stats", DEFAULT_MAX_PLAYER_STATS);
        if (maxPlayerStats != -1 && maxPlayerStats <= 0) {
            maxPlayerStats = DEFAULT_MAX_PLAYER_STATS;
        }

        String rawStorage = config.getString("settings.storage-type", "json").toLowerCase(Locale.ROOT);
        storageType = ("json".equals(rawStorage) || "sqlite".equals(rawStorage)) ? rawStorage : "json";
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public ConfigurationSection getMobsSection() {
        return config.getConfigurationSection("mobs");
    }

    // ===== Type-safe cached getters =====

    public boolean isDebug() {
        return debug;
    }

    public boolean isUseLootingEnchant() {
        return useLootingEnchant;
    }

    public double getLootingMultiplier() {
        return lootingMultiplier;
    }

    public boolean isGlobalAllowSpawnerDrops() {
        return globalAllowSpawnerDrops;
    }

    public boolean isInheritSpawnerTag() {
        return inheritSpawnerTag;
    }

    public double getSearchRadius() {
        return searchRadius;
    }

    public Sound getDropSound() {
        return dropSound;
    }

    public boolean isPlaySound() {
        return playSound;
    }

    public String getGlobalAnnouncement() {
        return globalAnnouncement;
    }

    public boolean isStatisticsEnabled() {
        return enableStatistics;
    }

    public int getAutoSaveInterval() {
        return autoSaveInterval;
    }

    public int getMaxPlayerStats() {
        return maxPlayerStats;
    }

    public String getStorageType() {
        return storageType;
    }

    /**
     * Update storage-type in both the in-memory config and the raw file.
     * Uses targeted line replacement to avoid SnakeYAML reformatting the entire file.
     */
    public void setStorageType(String type) {
        this.storageType = type;
        config.set("settings.storage-type", type);

        try {
            List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("storage-type:")) {
                    String original = lines.get(i);
                    String indent = original.substring(0, original.indexOf("storage-type:"));
                    lines.set(i, indent + "storage-type: " + type);
                    found = true;
                    break;
                }
            }
            if (found) {
                Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8);
            } else {
                // Don't fall back to config.save() — it reformats the entire file
                logger.warning("Could not find storage-type in config.yml — in-memory value updated but file not written. Add 'storage-type: " + type + "' under settings: manually.");
            }
        } catch (IOException e) {
            logger.warning("Failed to save config after storage type change: " + e.getMessage());
        }
    }

}
