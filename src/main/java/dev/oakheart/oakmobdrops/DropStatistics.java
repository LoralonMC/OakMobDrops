package dev.oakheart.oakmobdrops;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import dev.oakheart.oakmobdrops.model.PagedReport;
import dev.oakheart.oakmobdrops.util.FormatUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DropStatistics {
    private final Map<String, MobStats> mobStats = new ConcurrentHashMap<>();
    private final Map<String, ItemDropStats> dropStats = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStats> playerStats = new ConcurrentHashMap<>();
    private long totalKills = 0;
    private long totalEligibleKills = 0;
    private long totalDrops = 0;
    private LocalDateTime trackingStarted = LocalDateTime.now();

    private final JavaPlugin plugin;
    private final Object mu = new Object();  // Mutex for thread safety
    private BukkitTask autosaveTask;
    private int maxPlayerStats;  // Maximum player stats to track (-1 = unlimited)

    // Thread-safe Gson with LocalDateTime adapter
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
            .setPrettyPrinting()
            .create();

    /**
     * Create a new DropStatistics instance.
     * @param plugin the plugin instance
     * @param maxPlayerStats maximum number of player stats to track (-1 for unlimited)
     */
    public DropStatistics(JavaPlugin plugin, int maxPlayerStats) {
        this.plugin = plugin;
        this.maxPlayerStats = maxPlayerStats;
        load();
    }

    // Start autosave task
    public void startAutosave(int intervalMinutes) {
        if (intervalMinutes <= 0) return;

        // Cancel existing task if any
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }

        // Schedule new task - snapshot on main thread, write async
        autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Take snapshot on main thread
            StatsSaveData snapshot = createSnapshot();

            // Write async
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                saveSnapshot(snapshot);
            });
        }, 20L * 60 * intervalMinutes, 20L * 60 * intervalMinutes);
    }

    // Cancel autosave task
    public void stopAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
    }

    /**
     * Track ALL kills (before eligibility checks).
     * @param mob the mob type killed
     * @param fromSpawner whether the mob was from a spawner
     * @param killer the player who killed the mob (may be null)
     */
    public void recordKill(EntityType mob, boolean fromSpawner, Player killer) {
        synchronized (mu) {
            totalKills++;

            String mobKey = mob.name();
            MobStats stats = mobStats.computeIfAbsent(mobKey, k -> new MobStats());
            stats.totalKilled++;
            if (fromSpawner) stats.spawnerKills++;

            if (killer != null) {
                PlayerStats pStats = playerStats.computeIfAbsent(
                        killer.getUniqueId(),
                        k -> new PlayerStats(killer.getName())
                );
                pStats.mobsKilled++;
                if (fromSpawner) {
                    pStats.spawnerKills++;
                }

                // Initialize maps if null (for backwards compatibility with old stats)
                if (pStats.mobKillCounts == null) {
                    pStats.mobKillCounts = new HashMap<>();
                }
                if (pStats.mobSpawnerKills == null) {
                    pStats.mobSpawnerKills = new HashMap<>();
                }

                pStats.mobKillCounts.put(mobKey, pStats.mobKillCounts.getOrDefault(mobKey, 0L) + 1);

                if (fromSpawner) {
                    pStats.mobSpawnerKills.put(mobKey, pStats.mobSpawnerKills.getOrDefault(mobKey, 0L) + 1);
                }

                // Initialize firstSeen if null (backwards compatibility)
                if (pStats.firstSeen == null) {
                    pStats.firstSeen = pStats.lastSeen != null ? pStats.lastSeen : LocalDateTime.now();
                }

                pStats.playerName = killer.getName(); // Update name in case it changed
                pStats.lastSeen = LocalDateTime.now();

                // Enforce max player stats limit
                enforcePlayerStatsLimit();
            }
        }
    }

    // Track kills that passed eligibility checks
    public void recordEligibleKill(EntityType mob, Player killer) {
        synchronized (mu) {
            totalEligibleKills++;

            String mobKey = mob.name();
            MobStats stats = mobStats.computeIfAbsent(mobKey, k -> new MobStats());
            stats.eligibleKills++;

            // Track for player
            if (killer != null) {
                PlayerStats pStats = playerStats.get(killer.getUniqueId());
                if (pStats != null) {
                    pStats.eligibleKills++;

                    // Initialize map if null (backwards compatibility)
                    if (pStats.mobEligibleKills == null) {
                        pStats.mobEligibleKills = new HashMap<>();
                    }
                    pStats.mobEligibleKills.put(mobKey, pStats.mobEligibleKills.getOrDefault(mobKey, 0L) + 1);
                }
            }
        }
    }

    // Track drop attempt (before rolling)
    public void recordAttempt(EntityType mob, String dropId, double finalChance) {
        synchronized (mu) {
            String dropKey = mob.name() + ":" + dropId;
            ItemDropStats dStats = dropStats.computeIfAbsent(
                    dropKey,
                    k -> new ItemDropStats(mob.name(), dropId)
            );
            dStats.attempts++;
            dStats.expected += Math.max(0.0, Math.min(1.0, finalChance));
        }
    }

    // Track successful drop
    public void recordSuccess(EntityType mob, String dropId, int amount, double chance, Player recipient) {
        synchronized (mu) {
            totalDrops++;

            // Update mob stats
            String mobKey = mob.name();
            MobStats mStats = mobStats.computeIfAbsent(mobKey, k -> new MobStats());
            mStats.totalDropped++;

            // Update drop stats
            String dropKey = mobKey + ":" + dropId;
            ItemDropStats dStats = dropStats.computeIfAbsent(
                    dropKey,
                    k -> new ItemDropStats(mobKey, dropId)
            );
            dStats.timesDropped++;
            dStats.totalAmount += amount;
            dStats.lastDropped = LocalDateTime.now();

            // Update player stats
            if (recipient != null) {
                PlayerStats pStats = playerStats.computeIfAbsent(
                        recipient.getUniqueId(),
                        k -> new PlayerStats(recipient.getName())
                );
                pStats.dropsReceived++;

                // Initialize map if null (for backwards compatibility with old stats)
                if (pStats.dropCounts == null) {
                    pStats.dropCounts = new HashMap<>();
                }
                pStats.dropCounts.put(dropKey, pStats.dropCounts.getOrDefault(dropKey, 0L) + 1);

                pStats.playerName = recipient.getName(); // Update name
                pStats.lastSeen = LocalDateTime.now();

                // Track rarest drop
                if (chance < pStats.rarestDropChance) {
                    pStats.rarestDropChance = chance;
                    pStats.rarestDropName = dropId;
                }
            }
        }
    }

    // Create snapshot for saving (called on main thread)
    private StatsSaveData createSnapshot() {
        synchronized (mu) {
            StatsSaveData data = new StatsSaveData();

            // Deep copy the value objects so async writer cannot race with live mutations
            Map<String, MobStats> mobCopy = new HashMap<>(mobStats.size());
            for (Map.Entry<String, MobStats> e : mobStats.entrySet()) {
                var s = e.getValue();
                MobStats c = new MobStats();
                c.totalKilled = s.totalKilled;
                c.eligibleKills = s.eligibleKills;
                c.totalDropped = s.totalDropped;
                c.spawnerKills = s.spawnerKills;
                mobCopy.put(e.getKey(), c);
            }

            Map<String, ItemDropStats> dropCopy = new HashMap<>(dropStats.size());
            for (Map.Entry<String, ItemDropStats> e : dropStats.entrySet()) {
                var d = e.getValue();
                ItemDropStats c = new ItemDropStats();
                c.mobType = d.mobType;
                c.dropId = d.dropId;
                c.attempts = d.attempts;
                c.expected = d.expected;
                c.timesDropped = d.timesDropped;
                c.totalAmount = d.totalAmount;
                c.lastDropped = d.lastDropped; // LocalDateTime is immutable
                dropCopy.put(e.getKey(), c);
            }

            Map<UUID, PlayerStats> playerCopy = new HashMap<>(playerStats.size());
            for (Map.Entry<UUID, PlayerStats> e : playerStats.entrySet()) {
                var p = e.getValue();
                PlayerStats c = new PlayerStats();
                c.playerName = p.playerName;
                c.mobsKilled = p.mobsKilled;
                c.eligibleKills = p.eligibleKills;
                c.spawnerKills = p.spawnerKills;
                c.dropsReceived = p.dropsReceived;
                c.rarestDropChance = p.rarestDropChance;
                c.rarestDropName = p.rarestDropName;
                c.firstSeen = p.firstSeen; // LocalDateTime is immutable
                c.lastSeen = p.lastSeen; // LocalDateTime is immutable

                // Deep copy the new maps
                if (p.mobKillCounts != null) {
                    c.mobKillCounts = new HashMap<>(p.mobKillCounts);
                }
                if (p.mobEligibleKills != null) {
                    c.mobEligibleKills = new HashMap<>(p.mobEligibleKills);
                }
                if (p.mobSpawnerKills != null) {
                    c.mobSpawnerKills = new HashMap<>(p.mobSpawnerKills);
                }
                if (p.dropCounts != null) {
                    c.dropCounts = new HashMap<>(p.dropCounts);
                }

                playerCopy.put(e.getKey(), c);
            }

            data.mobStats = mobCopy;
            data.dropStats = dropCopy;
            data.playerStats = playerCopy;
            data.totalKills = totalKills;
            data.totalEligibleKills = totalEligibleKills;
            data.totalDrops = totalDrops;
            data.trackingStarted = trackingStarted;
            return data;
        }
    }

    // Save snapshot to file (can be called async)
    private void saveSnapshot(StatsSaveData data) {
        File dir = plugin.getDataFolder();
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }

        Path target = new File(dir, "statistics.json").toPath();
        Path tmp = new File(dir, "statistics.json.tmp").toPath();

        try (Writer writer = Files.newBufferedWriter(
                tmp,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            GSON.toJson(data, writer);
            writer.flush();
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write statistics tmp file: " + e.getMessage());
            return;
        }

        try {
            // Try atomic replace first
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                // Fallback to regular replace
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to move statistics file: " + ex.getMessage());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to move statistics file: " + e.getMessage());
        }
    }

    // Public save method (creates snapshot then saves)
    public void save() {
        StatsSaveData snapshot = createSnapshot();
        saveSnapshot(snapshot);
    }

    // Load statistics from file
    public void load() {
        File statsFile = new File(plugin.getDataFolder(), "statistics.json");
        if (!statsFile.exists()) return;

        try (Reader reader = Files.newBufferedReader(statsFile.toPath(), StandardCharsets.UTF_8)) {
            StatsSaveData data = GSON.fromJson(reader, StatsSaveData.class);
            if (data != null) {
                synchronized (mu) {
                    mobStats.clear();
                    mobStats.putAll(data.mobStats);
                    dropStats.clear();
                    dropStats.putAll(data.dropStats);
                    playerStats.clear();
                    playerStats.putAll(data.playerStats);
                    totalKills = data.totalKills;
                    totalEligibleKills = data.totalEligibleKills;
                    totalDrops = data.totalDrops;
                    trackingStarted = data.trackingStarted != null ?
                            data.trackingStarted : LocalDateTime.now();
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            plugin.getLogger().warning("Failed to load statistics: " + e.getMessage());
        }
    }

    /**
     * Generate report with MiniMessage formatting.
     * @param type the type of report to generate
     * @param limit number of entries per page
     * @param page page number (1-based)
     * @return paged report with lines and pagination metadata
     */
    public PagedReport generateReport(ReportType type, int limit, int page) {
        List<String> report = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        // Deep-copy snapshot for thread safety (prevents reading live mutable objects)
        StatsSaveData snapshot = createSnapshot();
        Map<String, MobStats> mobSnapshot = snapshot.mobStats;
        Map<String, ItemDropStats> dropSnapshot = snapshot.dropStats;
        Map<UUID, PlayerStats> playerSnapshot = snapshot.playerStats;
        long totalKills = snapshot.totalKills;
        long totalEligible = snapshot.totalEligibleKills;
        long totalDrops = snapshot.totalDrops;
        LocalDateTime started = snapshot.trackingStarted;

        // Calculate pagination
        int skip = (page - 1) * limit;
        int totalPages = 1;

        switch (type) {
            case OVERVIEW -> {
                report.add("<gold><bold>=== Drop Statistics Overview ===</bold></gold>");
                report.add("<gray>Tracking since:</gray> <white>" + started.format(formatter) + "</white>");
                report.add("<gray>Total mobs killed:</gray> <white>" + String.format("%,d", totalKills) + "</white>");
                report.add("<gray>Eligible for drops:</gray> <white>" + String.format("%,d", totalEligible) + "</white>");
                report.add("<gray>Total items dropped:</gray> <white>" + String.format("%,d", totalDrops) + "</white>");

                double eligibleRate = totalEligible > 0 ? (totalDrops * 100.0 / totalEligible) : 0;
                report.add("<gray>Drop rate (eligible):</gray> <white>" + FormatUtils.formatStatPercent(eligibleRate) + "</white>");
                report.add("<gray>Unique drops tracked:</gray> <white>" + dropSnapshot.size() + "</white>");
                report.add("<gray>Players tracked:</gray> <white>" + playerSnapshot.size() + "</white>");
            }

            case EXPECTED_VS_ACTUAL -> {
                var dropList = dropSnapshot.values().stream()
                        .filter(d -> d.attempts > 0)
                        .sorted((a, b) -> Double.compare(
                                b.expected / Math.max(1, b.attempts),
                                a.expected / Math.max(1, a.attempts)
                        ))
                        .toList();

                totalPages = (int) Math.ceil((double) dropList.size() / limit);
                report.add("<gold><bold>=== Expected vs Actual Drop Rates ===</bold></gold>");
                report.add("<gray>Page " + page + " of " + totalPages + " <dark_gray>(" + dropList.size() + " total)</dark_gray></gray>");

                dropList.stream()
                        .skip(skip)
                        .limit(limit)
                        .forEach(d -> {
                            double expRate = d.expected / Math.max(1, d.attempts);
                            double actRate = (double) d.timesDropped / Math.max(1, d.attempts);
                            double variance = expRate > 0 ? ((actRate - expRate) / expRate) * 100 : 0;

                            report.add("<yellow>" + d.dropId + "</yellow> <gray>from</gray> <red>" +
                                    FormatUtils.formatEnumName(d.mobType) + "</red>");
                            report.add("  <gray>Attempts:</gray> <white>" + String.format("%,d", d.attempts) +
                                    "</white> <gray>| Successes:</gray> <white>" + d.timesDropped +
                                    "</white> <gray>(≈" + Math.round(d.expected) + " expected)</gray>");
                            report.add("  <gray>Expected:</gray> <white>" + FormatUtils.formatStatPercent(expRate * 100) +
                                    "</white> <gray>| Actual:</gray> <white>" + FormatUtils.formatStatPercent(actRate * 100) +
                                    "</white>");

                            String varianceColor = variance >= 0 ? "green" : "red";
                            String varianceSign = variance >= 0 ? "+" : "";
                            report.add("  <gray>Variance:</gray> <" + varianceColor + ">" +
                                    varianceSign + FormatUtils.formatStatPercent(variance) + "</" + varianceColor + ">");
                        });
            }

            case RAREST_DROPS -> {
                var dropList = dropSnapshot.values().stream()
                        .sorted(Comparator.comparingLong(d -> d.timesDropped))
                        .toList();

                totalPages = (int) Math.ceil((double) dropList.size() / limit);
                report.add("<gold><bold>=== Rarest Drops (by fewest successes) ===</bold></gold>");
                report.add("<gray>Page " + page + " of " + totalPages + " <dark_gray>(" + dropList.size() + " total)</dark_gray></gray>");

                dropList.stream()
                        .skip(skip)
                        .limit(limit)
                        .forEach(d -> {
                            MobStats m = mobSnapshot.get(d.mobType);
                            long eligibleKillsForMob = (m != null) ? m.eligibleKills : 0;
                            double actual = (d.attempts > 0) ? (double) d.timesDropped / d.attempts : 0.0;
                            report.add("<yellow>" + d.dropId + "</yellow> <gray>from</gray> <red>" + FormatUtils.formatEnumName(d.mobType) + "</red>");
                            report.add("  <gray>Successes:</gray> <white>" + d.timesDropped + "</white>"
                                    + " <gray>| Attempts:</gray> <white>" + String.format("%,d", d.attempts) + "</white>"
                                    + " <gray>| Actual:</gray> <white>" + FormatUtils.formatStatPercent(actual * 100) + "</white>");
                            if (eligibleKillsForMob  > 0) {
                                report.add("  <gray>Eligible kills (mob):</gray> <white>" + String.format("%,d", eligibleKillsForMob ) + "</white>");
                            }
                        });
            }
            case TOP_FARMERS -> {
                var playerList = playerSnapshot.values().stream()
                        .sorted(Comparator.comparingLong((PlayerStats p) -> p.mobsKilled).reversed())
                        .toList();

                totalPages = (int) Math.ceil((double) playerList.size() / limit);
                report.add("<gold><bold>=== Top Farmers ===</bold></gold>");
                report.add("<gray>Page " + page + " of " + totalPages + " <dark_gray>(" + playerList.size() + " total)</dark_gray></gray>");

                playerList.stream()
                        .skip(skip)
                        .limit(limit)
                        .forEach(p -> {
                            double ratio = (p.mobsKilled > 0) ? (p.dropsReceived * 100.0 / p.mobsKilled) : 0.0;
                            report.add("<click:run_command:'/omd stats player " + p.playerName + "'><hover:show_text:'<gray>Click to view detailed stats</gray>'><yellow>" + p.playerName + "</yellow></hover></click>");
                            report.add("  <gray>Kills:</gray> <white>" + String.format("%,d", p.mobsKilled) + "</white>"
                                    + " <gray>| Drops:</gray> <white>" + String.format("%,d", p.dropsReceived) + "</white>"
                                    + " <gray>| Ratio:</gray> <white>" + FormatUtils.formatStatPercent(ratio) + "</white>");
                            if (p.rarestDropName != null) {
                                report.add("  <gray>Rarest:</gray> <aqua>" + p.rarestDropName + "</aqua>"
                                        + " <gray>(</gray><white>" + FormatUtils.formatStatPercent(p.rarestDropChance * 100) + "</white><gray>)</gray>");
                            }
                        });
            }
            case MOB_BREAKDOWN -> {
                var mobList = mobSnapshot.entrySet().stream()
                        .sorted(Comparator.comparingLong((Map.Entry<String, MobStats> e) -> e.getValue().totalKilled).reversed())
                        .toList();

                totalPages = (int) Math.ceil((double) mobList.size() / limit);
                report.add("<gold><bold>=== Mob Kill Statistics ===</bold></gold>");
                report.add("<gray>Page " + page + " of " + totalPages + " <dark_gray>(" + mobList.size() + " total)</dark_gray></gray>");

                mobList.stream()
                        .skip(skip)
                        .limit(limit)
                        .forEach(e -> {
                            var s = e.getValue();
                            double dropRate = (s.eligibleKills > 0) ? (s.totalDropped * 100.0 / s.eligibleKills) : 0.0;
                            double spawnerPct = (s.totalKilled > 0) ? (s.spawnerKills * 100.0 / s.totalKilled) : 0.0;
                            report.add("<yellow>" + FormatUtils.formatEnumName(e.getKey()) + "</yellow>");
                            report.add("  <gray>Killed:</gray> <white>" + String.format("%,d", s.totalKilled) + "</white>"
                                    + " <gray>| Eligible:</gray> <white>" + String.format("%,d", s.eligibleKills) + "</white>"
                                    + " <gray>| Dropped:</gray> <white>" + String.format("%,d", s.totalDropped) + "</white>"
                                    + " <gray>(</gray><white>" + FormatUtils.formatStatPercent(dropRate) + "</white><gray>)</gray>");
                            if (s.spawnerKills > 0) {
                                report.add("  <gray>Spawner kills:</gray> <white>" + String.format("%,d", s.spawnerKills) + "</white>"
                                        + " <gray>(</gray><white>" + FormatUtils.formatStatPercent(spawnerPct) + "</white><gray>)</gray>");
                            }
                        });
            }
        }

        return new PagedReport(report, page, totalPages, type);
    }

    /**
     * Generate a detailed statistics report for a specific player.
     * @param playerName the player name to look up
     * @return list of formatted report lines, or empty list if player not found
     */
    public List<String> generatePlayerReport(String playerName) {
        List<String> report = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        // Deep-copy snapshot for thread safety (prevents reading live mutable objects)
        StatsSaveData snapshot = createSnapshot();
        Map<String, MobStats> mobSnapshot = snapshot.mobStats;
        Map<String, ItemDropStats> dropSnapshot = snapshot.dropStats;

        // Find player by name (case-insensitive)
        PlayerStats player = null;
        for (PlayerStats ps : snapshot.playerStats.values()) {
            if (ps.playerName.equalsIgnoreCase(playerName)) {
                player = ps;
                break;
            }
        }

        if (player == null) {
            report.add("<red>No statistics found for player '" + playerName + "'</red>");
            return report;
        }

        // Header
        report.add("<gold><bold>=== Player Statistics: " + player.playerName + " ===</bold></gold>");
        if (player.firstSeen != null) {
            report.add("<gray>First tracked:</gray> <white>" + player.firstSeen.format(formatter) + "</white>");
        }
        report.add("<gray>Last seen:</gray> <white>" + player.lastSeen.format(formatter) + "</white>");
        report.add("");

        // Calculate favorites
        String favoriteMob = null;
        long favoriteMobKills = 0;
        if (player.mobKillCounts != null && !player.mobKillCounts.isEmpty()) {
            var favorite = player.mobKillCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
            if (favorite != null) {
                favoriteMob = FormatUtils.formatEnumName(favorite.getKey());
                favoriteMobKills = favorite.getValue();
            }
        }

        String favoriteDrop = null;
        long favoriteDropCount = 0;
        if (player.dropCounts != null && !player.dropCounts.isEmpty()) {
            var favorite = player.dropCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
            if (favorite != null) {
                String[] parts = favorite.getKey().split(":", 2);
                favoriteDrop = parts.length > 1 ? parts[1] : favorite.getKey();
                favoriteDropCount = favorite.getValue();
            }
        }

        // Overall stats
        report.add("<yellow><bold>Overall Statistics:</bold></yellow>");
        report.add("<gray>Total mobs killed:</gray> <white>" + String.format("%,d", player.mobsKilled) + "</white>");

        if (player.eligibleKills > 0) {
            double eligiblePct = (player.mobsKilled > 0) ? (player.eligibleKills * 100.0 / player.mobsKilled) : 0.0;
            report.add("<gray>Eligible kills:</gray> <white>" + String.format("%,d", player.eligibleKills) +
                    "</white> <gray>(</gray><white>" + FormatUtils.formatStatPercent(eligiblePct) + "</white><gray>)</gray>");
        }

        if (player.spawnerKills > 0) {
            double spawnerPct = (player.mobsKilled > 0) ? (player.spawnerKills * 100.0 / player.mobsKilled) : 0.0;
            report.add("<gray>Spawner kills:</gray> <white>" + String.format("%,d", player.spawnerKills) +
                    "</white> <gray>(</gray><white>" + FormatUtils.formatStatPercent(spawnerPct) + "</white><gray>)</gray>");
        }

        report.add("<gray>Total drops received:</gray> <white>" + String.format("%,d", player.dropsReceived) + "</white>");

        double ratio = (player.mobsKilled > 0) ? (player.dropsReceived * 100.0 / player.mobsKilled) : 0.0;
        report.add("<gray>Drop ratio:</gray> <white>" + FormatUtils.formatStatPercent(ratio) + "</white>");

        if (player.rarestDropName != null) {
            report.add("<gray>Rarest drop:</gray> <aqua>" + player.rarestDropName + "</aqua> " +
                    "<gray>(</gray><white>" + FormatUtils.formatStatPercent(player.rarestDropChance * 100) + "</white><gray>)</gray>");
        }

        // Show favorites
        if (favoriteMob != null) {
            report.add("<gray>Favorite mob:</gray> <red>" + favoriteMob + "</red> " +
                    "<gray>(</gray><white>" + String.format("%,d", favoriteMobKills) + " kills</white><gray>)</gray>");
        }
        if (favoriteDrop != null) {
            report.add("<gray>Most common drop:</gray> <aqua>" + favoriteDrop + "</aqua> " +
                    "<gray>(</gray><white>" + String.format("%,d", favoriteDropCount) + " times</white><gray>)</gray>");
        }

        report.add("");

        // Kills by mob type
        if (player.mobKillCounts != null && !player.mobKillCounts.isEmpty()) {
            report.add("<yellow><bold>Kills by Mob Type:</bold></yellow>");
            final Map<String, Long> eligibleMap = player.mobEligibleKills;
            final Map<String, Long> spawnerMap = player.mobSpawnerKills;
            player.mobKillCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> {
                        String mobType = e.getKey();
                        long totalKills = e.getValue();
                        long eligibleKills = (eligibleMap != null) ?
                                eligibleMap.getOrDefault(mobType, 0L) : 0L;
                        long spawnerKills = (spawnerMap != null) ?
                                spawnerMap.getOrDefault(mobType, 0L) : 0L;

                        // Compact format: Zombie: 543 (500 eligible, 100 spawner)
                        StringBuilder line = new StringBuilder();
                        line.append("  <gray>•</gray> <red>").append(FormatUtils.formatEnumName(mobType)).append(":</red> ");
                        line.append("<white>").append(String.format("%,d", totalKills)).append("</white>");

                        // Show details if available
                        if (eligibleKills > 0 || spawnerKills > 0) {
                            line.append(" <gray>(</gray>");
                            if (eligibleKills > 0) {
                                line.append("<white>").append(String.format("%,d", eligibleKills)).append(" eligible</white>");
                            }
                            if (spawnerKills > 0) {
                                if (eligibleKills > 0) line.append("<gray>, </gray>");
                                line.append("<white>").append(String.format("%,d", spawnerKills)).append(" spawner</white>");
                            }
                            line.append("<gray>)</gray>");
                        }

                        report.add(line.toString());
                    });
            report.add("");
        } else if (player.mobKillCounts == null) {
            report.add("<gray><i>(Detailed mob kill breakdown not available for this player - stats tracked from plugin update onwards)</i></gray>");
            report.add("");
        }

        // Drops received
        if (player.dropCounts != null && !player.dropCounts.isEmpty()) {
            report.add("<yellow><bold>Drops Received:</bold></yellow>");
            final long totalDropsReceived = player.dropsReceived;
            final int totalUniqueDrops = player.dropCounts.size();
            player.dropCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(15)  // Show top 15 drops
                    .forEach(e -> {
                        // Parse mob:dropId format
                        String[] parts = e.getKey().split(":", 2);
                        String mobType = parts.length > 0 ? FormatUtils.formatEnumName(parts[0]) : "Unknown";
                        String dropId = parts.length > 1 ? parts[1] : e.getKey();

                        double pct = (totalDropsReceived > 0) ? (e.getValue() * 100.0 / totalDropsReceived) : 0.0;
                        report.add("  <gray>•</gray> <aqua>" + dropId + "</aqua> <gray>from</gray> <red>" + mobType + ":</red> " +
                                "<white>" + String.format("%,d", e.getValue()) + "</white> " +
                                "<gray>(</gray><white>" + FormatUtils.formatStatPercent(pct) + "</white><gray>)</gray>");
                    });

            if (totalUniqueDrops > 15) {
                report.add("  <gray><i>... and " + (totalUniqueDrops - 15) + " more</i></gray>");
            }
        } else if (player.dropCounts == null) {
            report.add("<gray><i>(Detailed drop breakdown not available for this player - stats tracked from plugin update onwards)</i></gray>");
        }

        return report;
    }

    /**
     * Enforce max player stats limit by removing least active players.
     * Called internally when tracking new player data.
     */
    private void enforcePlayerStatsLimit() {
        if (maxPlayerStats <= 0 || playerStats.size() <= maxPlayerStats) {
            return;
        }

        // Remove players who haven't been seen recently (oldest first)
        int toRemove = playerStats.size() - maxPlayerStats;
        playerStats.entrySet().stream()
                .sorted((a, b) -> {
                    LocalDateTime aTime = a.getValue().lastSeen != null ? a.getValue().lastSeen : LocalDateTime.MIN;
                    LocalDateTime bTime = b.getValue().lastSeen != null ? b.getValue().lastSeen : LocalDateTime.MIN;
                    return aTime.compareTo(bTime);
                })
                .limit(toRemove)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(playerStats::remove);
    }

    /**
     * Prune old player data based on inactivity period.
     * @param daysInactive number of days of inactivity before pruning
     */
    public void pruneOldPlayers(int daysInactive) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysInactive);
        synchronized (mu) {
            playerStats.entrySet().removeIf(entry -> {
                PlayerStats stats = entry.getValue();
                return stats.lastSeen != null && stats.lastSeen.isBefore(cutoff);
            });
        }
    }

    /**
     * Update the max player stats limit.
     * @param maxPlayerStats the new limit (-1 for unlimited)
     */
    public void setMaxPlayerStats(int maxPlayerStats) {
        synchronized (mu) {
            this.maxPlayerStats = maxPlayerStats;
            enforcePlayerStatsLimit();
        }
    }

    // ===== Thread-safe counter getters (for PlaceholderAPI) =====

    public long getTotalKills() {
        synchronized (mu) { return totalKills; }
    }

    public long getTotalDrops() {
        synchronized (mu) { return totalDrops; }
    }

    public long getTotalEligibleKills() {
        synchronized (mu) { return totalEligibleKills; }
    }

    public long getPlayerKills(UUID playerId) {
        synchronized (mu) {
            PlayerStats p = playerStats.get(playerId);
            return p != null ? p.mobsKilled : 0;
        }
    }

    public long getPlayerDrops(UUID playerId) {
        synchronized (mu) {
            PlayerStats p = playerStats.get(playerId);
            return p != null ? p.dropsReceived : 0;
        }
    }

    public long getPlayerEligibleKills(UUID playerId) {
        synchronized (mu) {
            PlayerStats p = playerStats.get(playerId);
            return p != null ? p.eligibleKills : 0;
        }
    }

    // LocalDateTime adapter for Gson
    private static class LocalDateTimeAdapter implements JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {
        @Override
        public JsonElement serialize(LocalDateTime src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public LocalDateTime deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
            return LocalDateTime.parse(json.getAsString());
        }
    }

    // Data classes
    public static class MobStats {
        long totalKilled = 0;
        long eligibleKills = 0;  // Passed all checks
        long totalDropped = 0;
        long spawnerKills = 0;
    }

    public static class ItemDropStats {
        String mobType;
        String dropId;
        long attempts = 0;        // How many rolls
        double expected = 0.0;     // Sum of chances
        long timesDropped = 0;     // Actual successes
        long totalAmount = 0;
        LocalDateTime lastDropped;

        public ItemDropStats() {}
        public ItemDropStats(String mobType, String dropId) {
            this.mobType = mobType;
            this.dropId = dropId;
        }
    }

    public static class PlayerStats {
        String playerName;
        long mobsKilled = 0;
        long eligibleKills = 0;       // NEW: Kills that were eligible for drops
        long spawnerKills = 0;        // NEW: Kills from spawner mobs
        long dropsReceived = 0;
        double rarestDropChance = 1.0;
        String rarestDropName;
        LocalDateTime firstSeen;      // NEW: When player was first tracked
        LocalDateTime lastSeen;
        Map<String, Long> mobKillCounts = new HashMap<>();       // mob type -> kill count
        Map<String, Long> mobEligibleKills = new HashMap<>();    // mob type -> eligible kills
        Map<String, Long> mobSpawnerKills = new HashMap<>();     // mob type -> spawner kills
        Map<String, Long> dropCounts = new HashMap<>();          // drop ID -> times received

        public PlayerStats() {}
        public PlayerStats(String name) {
            this.playerName = name;
            this.firstSeen = LocalDateTime.now();
        }
    }

    private static class StatsSaveData {
        Map<String, MobStats> mobStats;
        Map<String, ItemDropStats> dropStats;
        Map<UUID, PlayerStats> playerStats;
        long totalKills;
        long totalEligibleKills;
        long totalDrops;
        LocalDateTime trackingStarted;
    }

    public enum ReportType {
        OVERVIEW,
        EXPECTED_VS_ACTUAL,
        RAREST_DROPS,
        TOP_FARMERS,
        MOB_BREAKDOWN
    }


    /**
     * Create a backup of the statistics file before resetting.
     * @return the backup file name, or null if backup failed or no file exists
     */
    public String backupBeforeReset() {
        File statsFile = new File(plugin.getDataFolder(), "statistics.json");
        if (!statsFile.exists()) {
            return null;
        }

        // Save current data first
        save();

        String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String backupName = "statistics-backup-" + timestamp + ".json";
        Path backupPath = new File(plugin.getDataFolder(), backupName).toPath();

        try {
            Files.copy(statsFile.toPath(), backupPath, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Statistics backed up to: " + backupName);
            return backupName;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to backup statistics: " + e.getMessage());
            return null;
        }
    }

    /**
     * Reset all statistics.
     * Clears all tracked data and saves the empty state.
     */
    public void reset() {
        synchronized (mu) {
            mobStats.clear();
            dropStats.clear();
            playerStats.clear();
            totalKills = 0;
            totalEligibleKills = 0;
            totalDrops = 0;
            trackingStarted = LocalDateTime.now();
        }
        save();
    }
}

