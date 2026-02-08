package dev.oakheart.oakmobdrops;

import dev.oakheart.oakmobdrops.announcement.AnnouncementManager;
import dev.oakheart.oakmobdrops.backend.*;
import dev.oakheart.oakmobdrops.command.CommandHandler;
import dev.oakheart.oakmobdrops.config.DropConfigLoader;
import dev.oakheart.oakmobdrops.drop.DropProcessor;
import dev.oakheart.oakmobdrops.listener.MobDropListener;
import dev.oakheart.oakmobdrops.model.*;
import dev.oakheart.oakmobdrops.util.CommandExecutor;
import dev.oakheart.oakmobdrops.util.FormatUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * OakMobDrops - Advanced mob drop system with multi-backend support and statistics.
 *
 * @author Loralon
 * @version 1.2.0
 */
public final class OakMobDrops extends JavaPlugin {

    // Configuration
    private Map<EntityType, MobDropConfig> mobConfigs = new HashMap<>();
    private boolean debugMode;
    private boolean useLootingEnchant;
    private double lootingMultiplier;
    private boolean globalAllowSpawnerDrops;
    private boolean inheritSpawnerTag;
    private double searchRadius;

    // Components
    private DropRouter router;
    private DropConfigLoader configLoader;
    private DropProcessor dropProcessor;
    private AnnouncementManager announcementManager;
    private DropStatistics statistics;
    private MiniMessage miniMessage;
    private CommandExecutor commandExecutor;

    // Statistics settings
    private boolean enableStatistics;
    private int autoSaveInterval;
    private int maxPlayerStats;

    // Spawner tracking
    private NamespacedKey spawnerMobKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Initialize namespaced key
        spawnerMobKey = new NamespacedKey(this, "from_spawner");

        // Load configuration
        loadConfiguration();

        // Register event listeners
        getServer().getPluginManager().registerEvents(
                new MobDropListener(this, spawnerMobKey), this);

        // Register command handler
        CommandHandler cmdHandler = new CommandHandler(this, miniMessage);
        var cmd = getCommand("oakmobdrops");
        if (cmd != null) {
            cmd.setExecutor(cmdHandler);
            cmd.setTabCompleter(cmdHandler);
        }

        // Log startup info
        getLogger().info("OakMobDrops v" + getPluginMeta().getVersion() + " has been enabled!");
        getLogger().info("Loaded " + mobConfigs.size() + " mob configurations.");

        int totalDrops = mobConfigs.values().stream()
                .mapToInt(config -> config.drops().size())
                .sum();
        getLogger().info("Total drop entries: " + totalDrops);

        if (debugMode) {
            getLogger().info("Debug mode is ENABLED - extra logging will be shown");
        }

        // Initialize bStats metrics
        initializeMetrics();

        // Register PlaceholderAPI hook if available
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new dev.oakheart.oakmobdrops.hook.PlaceholderAPIHook(this).register();
            getLogger().info("PlaceholderAPI integration enabled.");
        }
    }

    @Override
    public void onDisable() {
        if (statistics != null) {
            statistics.stopAutosave();
            statistics.save();
            getLogger().info("Statistics saved.");
        }

        getLogger().info("OakMobDrops has been disabled!");
    }

    /**
     * Initialize bStats metrics tracking.
     */
    private void initializeMetrics() {
        int pluginId = 28020;
        Metrics metrics = new Metrics(this, pluginId);

        metrics.addCustomChart(new SimplePie("most_used_backend", () -> {
            Map<String, Integer> backendCounts = new HashMap<>();

            for (MobDropConfig config : mobConfigs.values()) {
                for (DropEntry drop : config.drops()) {
                    String backendName = drop.spec().type().name();
                    backendCounts.put(backendName, backendCounts.getOrDefault(backendName, 0) + 1);
                }
            }

            if (backendCounts.isEmpty()) {
                return "None";
            }

            return backendCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("None");
        }));

        metrics.addCustomChart(new SimplePie("configured_mobs", () -> {
            int count = mobConfigs.size();
            if (count == 0) return "0";
            if (count <= 5) return "1-5";
            if (count <= 10) return "6-10";
            if (count <= 20) return "11-20";
            return "20+";
        }));

        metrics.addCustomChart(new SimplePie("statistics_enabled", () ->
            enableStatistics ? "Enabled" : "Disabled"
        ));

        metrics.addCustomChart(new SimplePie("spawner_drops", () ->
            globalAllowSpawnerDrops ? "Allowed" : "Blocked"
        ));

        if (debugMode) {
            getLogger().info("bStats metrics initialized successfully!");
        }
    }

    /**
     * Load or reload all configuration settings and components.
     * Package-private for use by CommandHandler reload.
     */
    public void loadConfiguration() {
        // Initialize MiniMessage if not already done
        if (miniMessage == null) {
            miniMessage = MiniMessage.miniMessage();
        }

        // Clear old caches
        if (announcementManager != null) {
            announcementManager.clearCaches();
        }

        // Load basic settings
        debugMode = getConfig().getBoolean("settings.debug", false);
        useLootingEnchant = getConfig().getBoolean("settings.use-looting-enchantment", true);
        lootingMultiplier = getConfig().getDouble("settings.looting-multiplier", 0.5);
        globalAllowSpawnerDrops = getConfig().getBoolean("settings.allow-spawner-drops", false);
        inheritSpawnerTag = getConfig().getBoolean("settings.inherit-spawner-tag", true);
        searchRadius = getConfig().getDouble("settings.drop-recipient-search-radius", 50.0);

        // Sound configuration
        String rawSound = getConfig().getString("settings.sound", "minecraft:ui.toast.challenge_complete");
        NamespacedKey soundKey = NamespacedKey.fromString(rawSound);
        Sound dropSound = (soundKey != null) ? Registry.SOUNDS.get(soundKey) : null;

        if (dropSound == null) {
            NamespacedKey fallbackKey = NamespacedKey.minecraft("ui.toast.challenge_complete");
            dropSound = Registry.SOUNDS.get(fallbackKey);
            getLogger().warning("Unknown sound '" + rawSound + "'; using minecraft:ui.toast.challenge_complete");
        }

        boolean playSound = getConfig().getBoolean("settings.play-sound", true);
        String globalAnnouncement = getConfig().getString("settings.global-announcement", "");

        // Statistics settings
        enableStatistics = getConfig().getBoolean("settings.enable-statistics", true);
        autoSaveInterval = getConfig().getInt("settings.statistics-autosave-interval", 5);
        maxPlayerStats = getConfig().getInt("settings.max-player-stats", 1000);

        // Initialize router (must be done before other components)
        if (router == null) {
            router = new DropRouter(this);

            List<String> availableBackends = new ArrayList<>();
            if (router.forType(DropType.EXECUTABLE_ITEMS).isPresent()) availableBackends.add("ExecutableItems");
            if (router.forType(DropType.ITEMSADDER).isPresent()) availableBackends.add("ItemsAdder");
            if (router.forType(DropType.NEXO).isPresent()) availableBackends.add("Nexo");
            availableBackends.add("Vanilla");

            if (availableBackends.size() == 1) {
                getLogger().info("Available backend: Vanilla only");
            } else {
                getLogger().info("Available backends: " + String.join(", ", availableBackends));
            }
        }

        // Initialize announcement manager
        NexoBackend nexoBackend = (NexoBackend) router.forType(DropType.NEXO);
        ItemsAdderBackend iaBackend = (ItemsAdderBackend) router.forType(DropType.ITEMSADDER);
        announcementManager = new AnnouncementManager(this, nexoBackend, iaBackend,
                globalAnnouncement, playSound, dropSound);

        // Initialize or update statistics
        if (enableStatistics) {
            if (statistics == null) {
                statistics = new DropStatistics(this, maxPlayerStats);
                statistics.startAutosave(autoSaveInterval);
                getLogger().info("Statistics tracking enabled (auto-save every " +
                        autoSaveInterval + " minutes)");
            } else {
                statistics.stopAutosave();
                statistics.setMaxPlayerStats(maxPlayerStats);
                statistics.startAutosave(autoSaveInterval);
            }
        } else {
            if (statistics != null) {
                statistics.stopAutosave();
            }
            getLogger().info("Statistics tracking disabled.");
        }

        // Initialize command executor
        commandExecutor = new CommandExecutor(this, debugMode);

        // Initialize drop processor
        dropProcessor = new DropProcessor(this, router, announcementManager, statistics,
                debugMode, useLootingEnchant, lootingMultiplier, enableStatistics);

        // Initialize config loader
        configLoader = new DropConfigLoader(this, debugMode);

        // Load mob configurations
        ConfigurationSection mobsSection = getConfig().getConfigurationSection("mobs");
        mobConfigs = configLoader.loadMobConfigs(mobsSection, globalAllowSpawnerDrops);

        // Validate backends and log summary
        validateDropBackends();
        logConfigSummary();
    }

    /**
     * Warn about drops that reference unavailable backends.
     */
    private void validateDropBackends() {
        for (var entry : mobConfigs.entrySet()) {
            EntityType mobType = entry.getKey();
            for (DropEntry drop : entry.getValue().drops()) {
                DropType type = drop.spec().type();
                if (type != DropType.VANILLA && !router.forType(type).isPresent()) {
                    getLogger().warning(FormatUtils.formatEntityName(mobType) + " drop '" +
                            drop.id() + "' uses " + type.name() + " backend, but " +
                            type.name() + " is not installed!");
                }
            }
        }
    }

    /**
     * Log a summary of loaded configurations at startup.
     */
    private void logConfigSummary() {
        if (mobConfigs.isEmpty()) {
            return;
        }

        for (var entry : mobConfigs.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .toList()) {
            EntityType mobType = entry.getKey();
            MobDropConfig config = entry.getValue();
            List<DropEntry> drops = config.drops();

            StringJoiner dropSummary = new StringJoiner(", ");
            for (DropEntry drop : drops) {
                dropSummary.add(drop.id() + " " +
                        FormatUtils.formatStatPercent(drop.chance() * 100));
            }

            getLogger().info(FormatUtils.formatEntityName(mobType) + ": " +
                    drops.size() + " drop" + (drops.size() != 1 ? "s" : "") +
                    " (" + dropSummary + ")");
        }
    }

    // ===== Getters for components =====

    public Map<EntityType, MobDropConfig> getMobConfigs() {
        return mobConfigs;
    }

    public DropRouter getRouter() {
        return router;
    }

    public DropProcessor getDropProcessor() {
        return dropProcessor;
    }

    public DropStatistics getStatistics() {
        return statistics;
    }

    public AnnouncementManager getAnnouncementManager() {
        return announcementManager;
    }

    public CommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    public MiniMessage getMiniMessage() {
        return miniMessage;
    }

    public boolean isStatisticsEnabled() {
        return enableStatistics;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public double getSearchRadius() {
        return searchRadius;
    }

    public boolean isInheritSpawnerTag() {
        return inheritSpawnerTag;
    }

    public NamespacedKey getSpawnerMobKey() {
        return spawnerMobKey;
    }
}
