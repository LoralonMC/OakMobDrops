package dev.oakheart.oakmobdrops;

import dev.oakheart.oakmobdrops.announcement.AnnouncementManager;
import dev.oakheart.oakmobdrops.backend.*;
import dev.oakheart.oakmobdrops.config.DropConfigLoader;
import dev.oakheart.oakmobdrops.drop.DropProcessor;
import dev.oakheart.oakmobdrops.model.*;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OakMobDrops - Advanced mob drop system with multi-backend support and statistics.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Multi-backend support (ExecutableItems, ItemsAdder, Nexo, Vanilla)</li>
 *   <li>Multiple independent drops per mob</li>
 *   <li>Looting enchantment support</li>
 *   <li>Spawner tracking with slime split inheritance</li>
 *   <li>Comprehensive statistics tracking</li>
 *   <li>Async announcement broadcasting</li>
 * </ul>
 *
 * @author Loralon
 * @version 1.0.0
 */
public final class OakMobDrops extends JavaPlugin implements Listener, TabCompleter {

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

    // Statistics settings
    private boolean enableStatistics;
    private int autoSaveInterval;
    private int maxPlayerStats;

    // Spawner tracking
    private NamespacedKey SPAWNER_MOB_KEY;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Initialize namespaced key
        SPAWNER_MOB_KEY = new NamespacedKey(this, "from_spawner");

        // Load configuration
        loadConfiguration();

        // Register event listeners
        getServer().getPluginManager().registerEvents(this, this);

        // Register command tab completer
        var cmd = getCommand("oakmobdrops");
        if (cmd != null) {
            cmd.setTabCompleter(this);
        }

        // Log startup info
        getLogger().info("OakMobDrops v" + getPluginMeta().getVersion() + " has been enabled!");
        getLogger().info("Loaded " + mobConfigs.size() + " mob configurations.");

        int totalDrops = mobConfigs.values().stream()
                .mapToInt(config -> config.getDrops().size())
                .sum();
        getLogger().info("Total drop entries: " + totalDrops);

        if (debugMode) {
            getLogger().info("Debug mode is ENABLED - extra logging will be shown");
        }

        // Initialize bStats metrics
        initializeMetrics();
    }

    @Override
    public void onDisable() {
        // Save statistics and clean up
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

        // Custom metric: Most used backend type
        metrics.addCustomChart(new SimplePie("most_used_backend", () -> {
            Map<String, Integer> backendCounts = new HashMap<>();

            for (MobDropConfig config : mobConfigs.values()) {
                for (DropEntry drop : config.getDrops()) {
                    String backendName = drop.getSpec().getType().name();
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

        // Custom metric: Number of configured mob types
        metrics.addCustomChart(new SimplePie("configured_mobs", () -> {
            int count = mobConfigs.size();
            if (count == 0) return "0";
            if (count <= 5) return "1-5";
            if (count <= 10) return "6-10";
            if (count <= 20) return "11-20";
            return "20+";
        }));

        // Custom metric: Statistics enabled
        metrics.addCustomChart(new SimplePie("statistics_enabled", () ->
            enableStatistics ? "Enabled" : "Disabled"
        ));

        // Custom metric: Spawner drops allowed
        metrics.addCustomChart(new SimplePie("spawner_drops", () ->
            globalAllowSpawnerDrops ? "Allowed" : "Blocked"
        ));

        if (debugMode) {
            getLogger().info("bStats metrics initialized successfully!");
        }
    }

    /**
     * Load or reload all configuration settings and components.
     */
    private void loadConfiguration() {
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

            // Log available backends
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

        // Initialize drop processor
        dropProcessor = new DropProcessor(this, router, announcementManager, statistics,
                debugMode, useLootingEnchant, lootingMultiplier, enableStatistics);

        // Initialize config loader
        configLoader = new DropConfigLoader(this, debugMode);

        // Load mob configurations
        ConfigurationSection mobsSection = getConfig().getConfigurationSection("mobs");
        mobConfigs = configLoader.loadMobConfigs(mobsSection, globalAllowSpawnerDrops);
    }

    /**
     * Tag mobs spawned from spawners for tracking.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            event.getEntity().getPersistentDataContainer().set(
                    SPAWNER_MOB_KEY,
                    PersistentDataType.BYTE,
                    (byte) 1
            );
            if (debugMode) {
                getLogger().info("Marked " + event.getEntityType() + " as spawner-spawned");
            }
        }
    }

    /**
     * Inherit spawner tag to slime children when they split.
     */
    @EventHandler(ignoreCancelled = true)
    public void onSlimeSplit(SlimeSplitEvent event) {
        if (!inheritSpawnerTag) {
            return;
        }

        var parent = event.getEntity();
        if (!parent.getPersistentDataContainer().has(SPAWNER_MOB_KEY, PersistentDataType.BYTE)) {
            return;
        }

        // Tag children on next tick
        Bukkit.getScheduler().runTask(this, () -> {
            var location = parent.getLocation();
            for (var child : parent.getWorld().getNearbyEntitiesByType(
                    org.bukkit.entity.Slime.class, location, 2.0, 2.0, 2.0)) {
                if (child.getTicksLived() <= 1) {
                    child.getPersistentDataContainer().set(SPAWNER_MOB_KEY,
                            PersistentDataType.BYTE, (byte) 1);
                    if (debugMode) {
                        getLogger().info("Inherited spawner tag to split slime");
                    }
                }
            }
        });
    }

    /**
     * Handle mob deaths and process drops.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        EntityType entityType = entity.getType();

        // Check if this mob has configured drops
        MobDropConfig config = mobConfigs.get(entityType);
        if (config == null) {
            return;
        }

        boolean fromSpawner = entity.getPersistentDataContainer().has(SPAWNER_MOB_KEY,
                PersistentDataType.BYTE);
        Player killer = entity.getKiller();

        // Record kill for statistics (before eligibility checks)
        if (statistics != null && enableStatistics) {
            statistics.recordKill(entityType, fromSpawner, killer);
        }

        // Apply eligibility checks
        if (fromSpawner && !config.isAllowSpawnerDrops()) {
            if (debugMode) {
                getLogger().info(entityType.name() + " was from spawner and spawner drops are disabled");
            }
            return;
        }

        if (config.isRequirePlayerKill() && killer == null) {
            if (debugMode) {
                getLogger().info(entityType.name() + " died but not killed by player");
            }
            return;
        }

        // Find recipient for drops
        Player recipient = (killer != null) ? killer : findNearestPlayer(entity);
        if (recipient == null) {
            if (debugMode) {
                getLogger().info("No player found to receive drops");
            }
            return;
        }

        // Mark as eligible kill
        if (statistics != null && enableStatistics) {
            statistics.recordEligibleKill(entityType, killer);
        }

        // Process all drops
        boolean anyDropOccurred = false;
        for (DropEntry drop : config.getDrops()) {
            if (dropProcessor.processDrop(drop, entity, recipient, killer)) {
                anyDropOccurred = true;
            }
        }

        if (debugMode && !anyDropOccurred) {
            getLogger().info("No drops succeeded for " + entityType.name());
        }
    }

    /**
     * Find the nearest player to an entity within the configured search radius.
     * @param entity the entity to search near
     * @return the nearest player, or null if none found
     */
    private Player findNearestPlayer(LivingEntity entity) {
        var location = entity.getLocation();
        return entity.getWorld().getNearbyPlayers(location, searchRadius)
                .stream()
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(location)))
                .orElse(null);
    }

    // ===== Command Handling =====

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("oakmobdrops")) {
            return Collections.emptyList();
        }

        // First argument - subcommands
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("oakmobdrops.reload")) completions.add("reload");
            if (sender.hasPermission("oakmobdrops.stats")) completions.add("stats");
            if (sender.hasPermission("oakmobdrops.test")) completions.add("test");
            if (sender.hasPermission("oakmobdrops.admin")) completions.add("clearstats");
            return filterStartsWith(completions, args[0]);
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);

        // Stats command completions
        if (subCommand.equals("stats") && sender.hasPermission("oakmobdrops.stats")) {
            if (args.length == 2) {
                return filterStartsWith(Arrays.asList(
                        "overview", "rarest", "mobs", "players", "variance"
                ), args[1]);
            }
            if (args.length == 3) {
                // If "players" is selected, suggest player names for per-player stats
                if (args[1].equalsIgnoreCase("players")) {
                    return filterStartsWith(
                            Bukkit.getOnlinePlayers().stream()
                                    .map(Player::getName)
                                    .sorted(String.CASE_INSENSITIVE_ORDER)
                                    .toList(),
                            args[2]
                    );
                }
                // Otherwise suggest page numbers
                return filterStartsWith(Arrays.asList("1", "2", "3", "4", "5"), args[2]);
            }
        }

        // Test command completions
        if (subCommand.equals("test") && sender.hasPermission("oakmobdrops.test")) {
            if (args.length == 2) {
                // Suggest configured mob types
                Set<String> mobs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (EntityType type : mobConfigs.keySet()) {
                    mobs.add(type.name().toLowerCase(Locale.ROOT));
                }
                return filterStartsWith(mobs, args[1]);
            }

            if (args.length == 3) {
                // Suggest drop IDs for the selected mob
                try {
                    EntityType type = EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));
                    MobDropConfig config = mobConfigs.get(type);
                    if (config != null) {
                        return filterStartsWith(
                                config.getDrops().stream().map(DropEntry::getId).toList(),
                                args[2]
                        );
                    }
                } catch (IllegalArgumentException ignored) {}
            }

            if (args.length == 4) {
                // Suggest online player names
                return filterStartsWith(
                        Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .toList(),
                        args[3]
                );
            }
        }

        return Collections.emptyList();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("oakmobdrops")) {
            return false;
        }

        // Show help if no arguments
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        // Handle subcommands
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                return handleReload(sender);
            }
            case "stats", "statistics" -> {
                return handleStats(sender, args);
            }
            case "test" -> {
                return handleTest(sender, args);
            }
            case "clearstats" -> {
                return handleClearStats(sender);
            }
            default -> {
                sender.sendMessage("§cUnknown subcommand. Use /oakmobdrops for help.");
                return true;
            }
        }
    }

    /**
     * Show command help.
     */
    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6§l=== OakMobDrops v" + getPluginMeta().getVersion() + " ===");
        sender.sendMessage("§e/oakmobdrops reload §7- Reload configuration");
        sender.sendMessage("§e/oakmobdrops stats [type] [page] §7- View statistics");
        sender.sendMessage("§e/oakmobdrops test <mob> <drop> §7- Test a drop");
        sender.sendMessage("§7Configured mobs: §f" + mobConfigs.size());
    }

    /**
     * Handle reload command.
     */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("oakmobdrops.reload")) {
            sender.sendMessage("§cYou don't have permission to reload the config!");
            return true;
        }

        reloadConfig();
        loadConfiguration();
        sender.sendMessage("§aConfiguration reloaded!");
        return true;
    }

    /**
     * Handle stats command.
     */
    private boolean handleStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("oakmobdrops.stats")) {
            sender.sendMessage("§cYou don't have permission to view statistics!");
            return true;
        }

        if (statistics == null || !enableStatistics) {
            sender.sendMessage("§cStatistics tracking is disabled!");
            return true;
        }

        // Handle per-player stats: /oakmobdrops stats players <playername>
        if (args.length >= 3 && args[1].equalsIgnoreCase("players")) {
            String playerName = args[2];
            List<String> report = statistics.generatePlayerReport(playerName);

            for (String line : report) {
                sender.sendMessage(miniMessage.deserialize(line));
            }
            return true;
        }

        // Handle paginated reports
        DropStatistics.ReportType reportType = DropStatistics.ReportType.OVERVIEW;
        int limit = 10;  // Always 10 entries per page
        int page = 1;

        if (args.length >= 2) {
            reportType = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "rarest" -> DropStatistics.ReportType.RAREST_DROPS;
                case "players" -> DropStatistics.ReportType.TOP_FARMERS;
                case "mobs" -> DropStatistics.ReportType.MOB_BREAKDOWN;
                case "variance" -> DropStatistics.ReportType.EXPECTED_VS_ACTUAL;
                default -> DropStatistics.ReportType.OVERVIEW;
            };
        }

        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
                page = Math.max(1, page);  // Ensure page is at least 1
            } catch (NumberFormatException ignored) {}
        }

        List<String> report = statistics.generateReport(reportType, limit, page);

        // Parse MiniMessage and send as Components for proper formatting
        for (String line : report) {
            sender.sendMessage(miniMessage.deserialize(line));
        }

        return true;
    }

    /**
     * Handle test command.
     */
    private boolean handleTest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("oakmobdrops.test")) {
            sender.sendMessage("§cYou don't have permission to test drops!");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /oakmobdrops test <mob> <drop-id> [player]");
            return true;
        }

        try {
            EntityType mobType = EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));
            String dropId = args[2];
            Player target = args.length >= 4
                    ? Bukkit.getPlayer(args[3])
                    : (sender instanceof Player ? (Player) sender : null);

            if (target == null) {
                sender.sendMessage("§cPlayer not found!");
                return true;
            }

            MobDropConfig config = mobConfigs.get(mobType);
            if (config == null) {
                sender.sendMessage("§cNo configuration for mob: " + mobType);
                return true;
            }

            DropEntry dropEntry = config.getDrops().stream()
                    .filter(d -> d.getId().equalsIgnoreCase(dropId))
                    .findFirst()
                    .orElse(null);

            if (dropEntry == null) {
                sender.sendMessage("§cDrop '" + dropId + "' not found for " + mobType);
                return true;
            }

            // Calculate amount
            int amount = dropEntry.getMinAmount() == dropEntry.getMaxAmount()
                    ? dropEntry.getMinAmount()
                    : ThreadLocalRandom.current().nextInt(dropEntry.getMinAmount(),
                            dropEntry.getMaxAmount() + 1);

            // Give the drop
            DropSpec spec = dropEntry.getSpec().withAmount(amount);
            DropBackend backend = router.forType(spec.getType());
            boolean success = backend.give(target, spec);

            if (success) {
                sender.sendMessage("§aSuccessfully gave " + dropId + " to " + target.getName());
            } else {
                sender.sendMessage("§cFailed to give drop (check console)");
            }

        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid mob type: " + args[1]);
        }

        return true;
    }

    /**
     * Handle clearstats command.
     */
    private boolean handleClearStats(CommandSender sender) {
        if (!sender.hasPermission("oakmobdrops.admin")) {
            sender.sendMessage("§cYou don't have permission!");
            return true;
        }

        if (statistics != null) {
            statistics.reset();
            sender.sendMessage("§eStatistics have been reset!");
        }
        return true;
    }

    /**
     * Filter a collection to only include strings starting with the given prefix.
     */
    private List<String> filterStartsWith(Collection<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>(options);
        }

        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .toList();
    }
}
