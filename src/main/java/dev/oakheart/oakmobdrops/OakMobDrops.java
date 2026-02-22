package dev.oakheart.oakmobdrops;

import dev.oakheart.oakmobdrops.announcement.AnnouncementManager;
import dev.oakheart.oakmobdrops.backend.DropRouter;
import dev.oakheart.oakmobdrops.config.ConfigManager;
import dev.oakheart.oakmobdrops.config.DropConfigLoader;
import dev.oakheart.oakmobdrops.data.JsonStatisticsDataStore;
import dev.oakheart.oakmobdrops.data.SQLiteStatisticsDataStore;
import dev.oakheart.oakmobdrops.data.StatisticsDataStore;
import dev.oakheart.oakmobdrops.drop.DropProcessor;
import dev.oakheart.oakmobdrops.listeners.MobDropListener;
import dev.oakheart.oakmobdrops.message.MessageManager;
import dev.oakheart.oakmobdrops.model.*;
import dev.oakheart.oakmobdrops.statistics.DropStatistics;
import dev.oakheart.oakmobdrops.util.CommandExecutor;
import dev.oakheart.oakmobdrops.util.FormatUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public final class OakMobDrops extends JavaPlugin {

    // Configuration
    private ConfigManager configManager;
    private MessageManager messageManager;
    private Map<EntityType, MobDropConfig> mobConfigs = new HashMap<>();

    // Components
    private DropRouter router;
    private DropConfigLoader configLoader;
    private DropProcessor dropProcessor;
    private AnnouncementManager announcementManager;
    private DropStatistics statistics;
    private MiniMessage miniMessage;
    private CommandExecutor commandExecutor;

    // Spawner tracking
    private NamespacedKey spawnerMobKey;

    @Override
    public void onEnable() {
        try {
            initializeComponents();
            registerListeners();
            registerCommands();
            initializeMetrics();
            registerPlaceholders();

            getLogger().info("OakMobDrops v" + getPluginMeta().getVersion() + " has been enabled!");
            getLogger().info("Loaded " + mobConfigs.size() + " mob configurations.");

            int totalDrops = mobConfigs.values().stream()
                    .mapToInt(config -> config.drops().size())
                    .sum();
            getLogger().info("Total drop entries: " + totalDrops);

            if (configManager.isDebug()) {
                getLogger().info("Debug mode is ENABLED - extra logging will be shown");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable OakMobDrops", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // Statistics — save data, stop autosave task, close data store
        if (statistics != null) {
            statistics.stopAutosave();
            statistics.save();
            statistics.getDataStore().close();
        }

        // Other components (configManager, messageManager, router, dropProcessor, etc.)
        // are plain objects with no shutdown logic — Paper handles listener/command cleanup.

        getLogger().info("OakMobDrops has been disabled!");
    }

    private void initializeComponents() {
        spawnerMobKey = new NamespacedKey(this, "from_spawner");

        configManager = new ConfigManager(this);
        configManager.load();

        messageManager = new MessageManager();
        messageManager.load(configManager.getConfig());

        // Load all components from configuration
        loadConfiguration();
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new MobDropListener(this, spawnerMobKey), this);
    }

    private void registerCommands() {
        new dev.oakheart.oakmobdrops.commands.OakMobDropsCommand(this, miniMessage, messageManager).register();
    }

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
            configManager.isStatisticsEnabled() ? "Enabled" : "Disabled"
        ));

        metrics.addCustomChart(new SimplePie("spawner_drops", () ->
            configManager.isGlobalAllowSpawnerDrops() ? "Allowed" : "Blocked"
        ));

        if (configManager.isDebug()) {
            getLogger().info("bStats metrics initialized successfully!");
        }
    }

    private void registerPlaceholders() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new dev.oakheart.oakmobdrops.hook.PlaceholderAPIHook(this).register();
            getLogger().info("PlaceholderAPI integration enabled.");
        } else {
            getLogger().info("PlaceholderAPI not found. Placeholders will not be available.");
        }
    }

    public void loadConfiguration() {
        // Initialize MiniMessage if not already done
        if (miniMessage == null) {
            miniMessage = MiniMessage.miniMessage();
        }

        // Reload messages
        if (messageManager != null) {
            messageManager.load(configManager.getConfig());
        }

        // Read cached values from ConfigManager
        boolean debugMode = configManager.isDebug();
        boolean useLootingEnchant = configManager.isUseLootingEnchant();
        double lootingMultiplier = configManager.getLootingMultiplier();
        boolean globalAllowSpawnerDrops = configManager.isGlobalAllowSpawnerDrops();
        boolean enableStatistics = configManager.isStatisticsEnabled();
        int autoSaveInterval = configManager.getAutoSaveInterval();
        int maxPlayerStats = configManager.getMaxPlayerStats();

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
        announcementManager = new AnnouncementManager(this, router,
                configManager.getGlobalAnnouncement(), configManager.isPlaySound(),
                configManager.getDropSound());

        // Initialize or update statistics
        if (enableStatistics) {
            if (statistics == null) {
                StatisticsDataStore dataStore = createDataStore(configManager.getStorageType());
                statistics = new DropStatistics(this, maxPlayerStats, dataStore);
                statistics.startAutosave(autoSaveInterval);
                getLogger().info("Statistics tracking enabled (" + dataStore.getTypeName() +
                        " backend, auto-save every " + autoSaveInterval + " minutes)");
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
        dropProcessor = new DropProcessor(this, router, announcementManager, commandExecutor,
                statistics, debugMode, useLootingEnchant, lootingMultiplier, enableStatistics);

        // Initialize config loader
        configLoader = new DropConfigLoader(this, debugMode);

        // Load mob configurations
        ConfigurationSection mobsSection = configManager.getMobsSection();
        mobConfigs = configLoader.loadMobConfigs(mobsSection, globalAllowSpawnerDrops);

        // Validate backends and log summary
        validateDropBackends();
        logConfigSummary();
    }

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

    /**
     * Create and initialize the appropriate data store based on config.
     * Handles auto-migration from JSON if storage-type is sqlite but statistics.json exists.
     */
    private StatisticsDataStore createDataStore(String storageType) {
        try {
            if ("sqlite".equals(storageType)) {
                File jsonFile = new File(getDataFolder(), "statistics.json");
                File dbFile = new File(getDataFolder(), "statistics.db");

                // Auto-migrate: JSON file exists but no DB yet
                if (jsonFile.exists() && !dbFile.exists()) {
                    getLogger().info("Auto-migrating statistics from JSON to SQLite...");
                    JsonStatisticsDataStore jsonStore = new JsonStatisticsDataStore(getDataFolder(), getLogger());
                    jsonStore.initialize();
                    DropStatistics.StatsSaveData allData = jsonStore.loadAll();
                    jsonStore.close();

                    SQLiteStatisticsDataStore sqliteStore = new SQLiteStatisticsDataStore(getDataFolder(), getLogger());
                    sqliteStore.initialize();
                    sqliteStore.saveAll(allData);

                    // Rename old JSON file
                    File migrated = new File(getDataFolder(), "statistics.json.migrated");
                    //noinspection ResultOfMethodCallIgnored
                    jsonFile.renameTo(migrated);
                    getLogger().info("Migration complete. Old data backed up as statistics.json.migrated");
                    return sqliteStore;
                }

                SQLiteStatisticsDataStore sqliteStore = new SQLiteStatisticsDataStore(getDataFolder(), getLogger());
                sqliteStore.initialize();
                return sqliteStore;
            }

            // Default: JSON
            JsonStatisticsDataStore jsonStore = new JsonStatisticsDataStore(getDataFolder(), getLogger());
            jsonStore.initialize();
            return jsonStore;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize " + storageType + " data store, falling back to JSON", e);
            JsonStatisticsDataStore fallback = new JsonStatisticsDataStore(getDataFolder(), getLogger());
            fallback.initialize();
            return fallback;
        }
    }

    // ===== Getters =====

    public Map<EntityType, MobDropConfig> getMobConfigs() {
        return Collections.unmodifiableMap(mobConfigs);
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

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public boolean isStatisticsEnabled() {
        return configManager.isStatisticsEnabled();
    }

    public boolean isDebugMode() {
        return configManager.isDebug();
    }

    public double getSearchRadius() {
        return configManager.getSearchRadius();
    }

    public boolean isInheritSpawnerTag() {
        return configManager.isInheritSpawnerTag();
    }

    public NamespacedKey getSpawnerMobKey() {
        return spawnerMobKey;
    }
}
