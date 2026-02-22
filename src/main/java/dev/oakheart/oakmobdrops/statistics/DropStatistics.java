package dev.oakheart.oakmobdrops.statistics;

import dev.oakheart.oakmobdrops.data.StatisticsDataStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
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

    // Data store (JSON or SQLite)
    private StatisticsDataStore dataStore;

    // Dirty tracking — only flush changed data
    private final Set<String> dirtyMobs = ConcurrentHashMap.newKeySet();
    private final Set<String> dirtyDrops = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();
    private volatile boolean dirtyGlobals = false;
    private final Set<UUID> deletedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Create a new DropStatistics instance.
     * @param plugin the plugin instance
     * @param maxPlayerStats maximum number of player stats to track (-1 for unlimited)
     * @param dataStore the storage backend
     */
    public DropStatistics(JavaPlugin plugin, int maxPlayerStats, StatisticsDataStore dataStore) {
        this.plugin = plugin;
        this.maxPlayerStats = maxPlayerStats;
        this.dataStore = dataStore;
        load();
    }

    // Start autosave task — uses dirty tracking to skip no-op cycles
    public void startAutosave(int intervalMinutes) {
        if (intervalMinutes <= 0) return;

        // Cancel existing task if any
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }

        autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirtyData,
                20L * 60 * intervalMinutes, 20L * 60 * intervalMinutes);
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
            dirtyGlobals = true;

            String mobKey = mob.name();
            MobStats stats = mobStats.computeIfAbsent(mobKey, k -> new MobStats());
            stats.totalKilled++;
            if (fromSpawner) stats.spawnerKills++;
            dirtyMobs.add(mobKey);

            if (killer != null) {
                UUID killerId = killer.getUniqueId();
                PlayerStats pStats = playerStats.computeIfAbsent(
                        killerId,
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
                dirtyPlayers.add(killerId);

                // Enforce max player stats limit
                enforcePlayerStatsLimit();
            }
        }
    }

    // Track kills that passed eligibility checks
    public void recordEligibleKill(EntityType mob, Player killer) {
        synchronized (mu) {
            totalEligibleKills++;
            dirtyGlobals = true;

            String mobKey = mob.name();
            MobStats stats = mobStats.computeIfAbsent(mobKey, k -> new MobStats());
            stats.eligibleKills++;
            dirtyMobs.add(mobKey);

            // Track for player
            if (killer != null) {
                UUID killerId = killer.getUniqueId();
                PlayerStats pStats = playerStats.get(killerId);
                if (pStats != null) {
                    pStats.eligibleKills++;

                    // Initialize map if null (backwards compatibility)
                    if (pStats.mobEligibleKills == null) {
                        pStats.mobEligibleKills = new HashMap<>();
                    }
                    pStats.mobEligibleKills.put(mobKey, pStats.mobEligibleKills.getOrDefault(mobKey, 0L) + 1);
                    dirtyPlayers.add(killerId);
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
            dirtyDrops.add(dropKey);
        }
    }

    // Track successful drop
    public void recordSuccess(EntityType mob, String dropId, int amount, double chance, Player recipient) {
        synchronized (mu) {
            totalDrops++;
            dirtyGlobals = true;

            // Update mob stats
            String mobKey = mob.name();
            MobStats mStats = mobStats.computeIfAbsent(mobKey, k -> new MobStats());
            mStats.totalDropped++;
            dirtyMobs.add(mobKey);

            // Update drop stats
            String dropKey = mobKey + ":" + dropId;
            ItemDropStats dStats = dropStats.computeIfAbsent(
                    dropKey,
                    k -> new ItemDropStats(mobKey, dropId)
            );
            dStats.timesDropped++;
            dStats.totalAmount += amount;
            dStats.lastDropped = LocalDateTime.now();
            dirtyDrops.add(dropKey);

            // Update player stats
            if (recipient != null) {
                UUID recipientId = recipient.getUniqueId();
                PlayerStats pStats = playerStats.computeIfAbsent(
                        recipientId,
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
                dirtyPlayers.add(recipientId);
            }
        }
    }

    /**
     * Flush only dirty data to the data store. Called by autosave timer.
     * Snapshots dirty sets on the calling thread, then writes async.
     */
    public void flushDirtyData() {
        // Snapshot and clear dirty flags
        Set<String> dMobs;
        Set<String> dDrops;
        Set<UUID> dPlayers;
        Set<UUID> dDeleted;
        boolean dGlobals;

        synchronized (mu) {
            if (dirtyMobs.isEmpty() && dirtyDrops.isEmpty() && dirtyPlayers.isEmpty()
                    && !dirtyGlobals && deletedPlayers.isEmpty()) {
                return;
            }
            dMobs = new HashSet<>(dirtyMobs);
            dirtyMobs.clear();
            dDrops = new HashSet<>(dirtyDrops);
            dirtyDrops.clear();
            dPlayers = new HashSet<>(dirtyPlayers);
            dirtyPlayers.clear();
            dGlobals = dirtyGlobals;
            dirtyGlobals = false;
            dDeleted = new HashSet<>(deletedPlayers);
            deletedPlayers.clear();
        }

        // Don't send dirty data for players that were deleted in the same cycle
        dPlayers.removeAll(dDeleted);

        // Deep copy dirty data under lock
        Map<String, MobStats> mobData = new HashMap<>();
        Map<String, ItemDropStats> dropData = new HashMap<>();
        Map<UUID, PlayerStats> playerData = new HashMap<>();
        long snapshotTotalKills, snapshotTotalEligibleKills, snapshotTotalDrops;
        LocalDateTime snapshotTrackingStarted;

        synchronized (mu) {
            for (String key : dMobs) {
                MobStats s = mobStats.get(key);
                if (s != null) mobData.put(key, copyMobStats(s));
            }
            for (String key : dDrops) {
                ItemDropStats s = dropStats.get(key);
                if (s != null) dropData.put(key, copyItemDropStats(s));
            }
            for (UUID uuid : dPlayers) {
                PlayerStats s = playerStats.get(uuid);
                if (s != null) playerData.put(uuid, copyPlayerStats(s));
            }
            snapshotTotalKills = totalKills;
            snapshotTotalEligibleKills = totalEligibleKills;
            snapshotTotalDrops = totalDrops;
            snapshotTrackingStarted = trackingStarted;
        }

        // Write async — batch all mutations into a single disk write
        final boolean globalsToSave = dGlobals;
        final Set<UUID> deletedToSave = dDeleted;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                dataStore.beginBatch();
                if (!deletedToSave.isEmpty()) dataStore.deletePlayerStats(deletedToSave);
                if (!mobData.isEmpty()) dataStore.saveMobStats(mobData);
                if (!dropData.isEmpty()) dataStore.saveDropStats(dropData);
                if (!playerData.isEmpty()) dataStore.savePlayerStats(playerData);
                if (globalsToSave) dataStore.saveGlobalStats(snapshotTotalKills,
                        snapshotTotalEligibleKills, snapshotTotalDrops, snapshotTrackingStarted);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to flush statistics: " + e.getMessage());
            } finally {
                dataStore.endBatch();
            }
        });
    }

    // Create snapshot for saving (called on main thread or under lock)
    private StatsSaveData createSnapshot() {
        synchronized (mu) {
            StatsSaveData data = new StatsSaveData();

            // Deep copy the value objects so async writer cannot race with live mutations
            Map<String, MobStats> mobCopy = new HashMap<>(mobStats.size());
            for (Map.Entry<String, MobStats> e : mobStats.entrySet()) {
                mobCopy.put(e.getKey(), copyMobStats(e.getValue()));
            }

            Map<String, ItemDropStats> dropCopy = new HashMap<>(dropStats.size());
            for (Map.Entry<String, ItemDropStats> e : dropStats.entrySet()) {
                dropCopy.put(e.getKey(), copyItemDropStats(e.getValue()));
            }

            Map<UUID, PlayerStats> playerCopy = new HashMap<>(playerStats.size());
            for (Map.Entry<UUID, PlayerStats> e : playerStats.entrySet()) {
                playerCopy.put(e.getKey(), copyPlayerStats(e.getValue()));
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

    // Public save method — synchronous full save (used by onDisable, backupBeforeReset)
    public void save() {
        StatsSaveData snapshot = createSnapshot();
        synchronized (mu) {
            dirtyMobs.clear();
            dirtyDrops.clear();
            dirtyPlayers.clear();
            dirtyGlobals = false;
            deletedPlayers.clear();
        }
        dataStore.saveAll(snapshot);
    }

    // Load statistics from data store
    public void load() {
        StatsSaveData data = dataStore.loadAll();
        if (data != null) {
            synchronized (mu) {
                mobStats.clear();
                if (data.mobStats != null) mobStats.putAll(data.mobStats);
                dropStats.clear();
                if (data.dropStats != null) dropStats.putAll(data.dropStats);
                playerStats.clear();
                if (data.playerStats != null) playerStats.putAll(data.playerStats);
                totalKills = data.totalKills;
                totalEligibleKills = data.totalEligibleKills;
                totalDrops = data.totalDrops;
                trackingStarted = data.trackingStarted != null ?
                        data.trackingStarted : LocalDateTime.now();
            }
        }
    }

    /**
     * Generate report with MiniMessage formatting.
     * Report format strings are intentionally hardcoded here (not in messages config)
     * because they are admin-only diagnostic output, not player-facing messages.
     * @param type the type of report to generate
     * @param limit number of entries per page
     * @param page page number (1-based)
     * @return paged report with lines and pagination metadata
     */
    public PagedReport generateReport(ReportType type, int limit, int page) {
        List<String> report = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        // Read under lock — these methods run on the main thread, so direct reads are safe
        final Map<String, MobStats> mobSnapshot;
        final Map<String, ItemDropStats> dropSnapshot;
        final Map<UUID, PlayerStats> playerSnapshot;
        final long totalKills;
        final long totalEligible;
        final long totalDrops;
        final LocalDateTime started;
        synchronized (mu) {
            mobSnapshot = new HashMap<>(mobStats);
            dropSnapshot = new HashMap<>(dropStats);
            playerSnapshot = new HashMap<>(playerStats);
            totalKills = this.totalKills;
            totalEligible = this.totalEligibleKills;
            totalDrops = this.totalDrops;
            started = this.trackingStarted;
        }

        // Pagination — skip is computed per-case after clamping page
        int totalPages = 1;

        switch (type) {
            case OVERVIEW -> {
                report.add("<#FCD472><bold>=== Drop Statistics Overview ===</bold>");
                report.add("<#6C757D>Tracking since: <#f2ebd7>" + started.format(formatter));
                report.add("<#6C757D>Total mobs killed: <#f2ebd7>" + String.format("%,d", totalKills));
                report.add("<#6C757D>Eligible for drops: <#f2ebd7>" + String.format("%,d", totalEligible));
                report.add("<#6C757D>Total items dropped: <#f2ebd7>" + String.format("%,d", totalDrops));

                double eligibleRate = totalEligible > 0 ? (totalDrops * 100.0 / totalEligible) : 0;
                report.add("<#6C757D>Drop rate (eligible): <#f2ebd7>" + FormatUtils.formatStatPercent(eligibleRate));
                report.add("<#6C757D>Unique drops tracked: <#f2ebd7>" + dropSnapshot.size());
                report.add("<#6C757D>Players tracked: <#f2ebd7>" + playerSnapshot.size());
            }

            case EXPECTED_VS_ACTUAL -> {
                var dropList = dropSnapshot.values().stream()
                        .filter(d -> d.attempts > 0)
                        .sorted((a, b) -> Double.compare(
                                b.expected / Math.max(1, b.attempts),
                                a.expected / Math.max(1, a.attempts)
                        ))
                        .toList();

                totalPages = Math.max(1, (int) Math.ceil((double) dropList.size() / limit));
                page = Math.max(1, Math.min(page, totalPages));
                report.add("<#FCD472><bold>=== Expected vs Actual Drop Rates ===</bold>");
                report.add("<#6C757D>Page " + page + " of " + totalPages + " (" + dropList.size() + " total)");

                dropList.stream()
                        .skip((long) (page - 1) * limit)
                        .limit(limit)
                        .forEach(d -> {
                            double expRate = d.expected / Math.max(1, d.attempts);
                            double actRate = (double) d.timesDropped / Math.max(1, d.attempts);
                            double variance = expRate > 0 ? ((actRate - expRate) / expRate) * 100 : 0;

                            report.add("<#FCD472><drop_" + d.dropId + "> <#6C757D>from <#D89B6A>" +
                                    FormatUtils.formatEnumName(d.mobType));
                            report.add("  <#6C757D>Attempts: <#f2ebd7>" + String.format("%,d", d.attempts) +
                                    " <#6C757D>| Successes: <#f2ebd7>" + d.timesDropped +
                                    " <#6C757D>(≈" + Math.round(d.expected) + " expected)");
                            report.add("  <#6C757D>Expected: <#f2ebd7>" + FormatUtils.formatStatPercent(expRate * 100) +
                                    " <#6C757D>| Actual: <#f2ebd7>" + FormatUtils.formatStatPercent(actRate * 100));

                            String varianceColor = variance >= 0 ? "<#8FAA87>" : "<#C27B6B>";
                            String varianceSign = variance >= 0 ? "+" : "";
                            report.add("  <#6C757D>Variance: " + varianceColor +
                                    varianceSign + FormatUtils.formatStatPercent(variance));
                        });
            }

            case RAREST_DROPS -> {
                var dropList = dropSnapshot.values().stream()
                        .sorted(Comparator.comparingLong(d -> d.timesDropped))
                        .toList();

                totalPages = Math.max(1, (int) Math.ceil((double) dropList.size() / limit));
                page = Math.max(1, Math.min(page, totalPages));
                report.add("<#FCD472><bold>=== Rarest Drops (by fewest successes) ===</bold>");
                report.add("<#6C757D>Page " + page + " of " + totalPages + " (" + dropList.size() + " total)");

                dropList.stream()
                        .skip((long) (page - 1) * limit)
                        .limit(limit)
                        .forEach(d -> {
                            MobStats m = mobSnapshot.get(d.mobType);
                            long eligibleKillsForMob = (m != null) ? m.eligibleKills : 0;
                            double actual = (d.attempts > 0) ? (double) d.timesDropped / d.attempts : 0.0;
                            report.add("<#FCD472><drop_" + d.dropId + "> <#6C757D>from <#D89B6A>" + FormatUtils.formatEnumName(d.mobType));
                            report.add("  <#6C757D>Successes: <#f2ebd7>" + d.timesDropped
                                    + " <#6C757D>| Attempts: <#f2ebd7>" + String.format("%,d", d.attempts)
                                    + " <#6C757D>| Actual: <#f2ebd7>" + FormatUtils.formatStatPercent(actual * 100));
                            if (eligibleKillsForMob > 0) {
                                report.add("  <#6C757D>Eligible kills (mob): <#f2ebd7>" + String.format("%,d", eligibleKillsForMob));
                            }
                        });
            }
            case TOP_FARMERS -> {
                var playerList = playerSnapshot.values().stream()
                        .sorted(Comparator.comparingLong((PlayerStats p) -> p.mobsKilled).reversed())
                        .toList();

                totalPages = Math.max(1, (int) Math.ceil((double) playerList.size() / limit));
                page = Math.max(1, Math.min(page, totalPages));
                report.add("<#FCD472><bold>=== Top Farmers ===</bold>");
                report.add("<#6C757D>Page " + page + " of " + totalPages + " (" + playerList.size() + " total)");

                playerList.stream()
                        .skip((long) (page - 1) * limit)
                        .limit(limit)
                        .forEach(p -> {
                            double ratio = (p.mobsKilled > 0) ? (p.dropsReceived * 100.0 / p.mobsKilled) : 0.0;
                            report.add("<click:run_command:'/omd stats player " + p.playerName + "'><hover:show_text:'<#6C757D>Click to view detailed stats'><#FCD472>" + p.playerName + "</hover></click>");
                            report.add("  <#6C757D>Kills: <#f2ebd7>" + String.format("%,d", p.mobsKilled)
                                    + " <#6C757D>| Drops: <#f2ebd7>" + String.format("%,d", p.dropsReceived)
                                    + " <#6C757D>| Ratio: <#f2ebd7>" + FormatUtils.formatStatPercent(ratio));
                            if (p.rarestDropName != null) {
                                report.add("  <#6C757D>Rarest: <#8FAA87><drop_" + p.rarestDropName
                                        + "> <#6C757D>(<#f2ebd7>" + FormatUtils.formatStatPercent(p.rarestDropChance * 100) + "<#6C757D>)");
                            }
                        });
            }
            case MOB_BREAKDOWN -> {
                var mobList = mobSnapshot.entrySet().stream()
                        .sorted(Comparator.comparingLong((Map.Entry<String, MobStats> e) -> e.getValue().totalKilled).reversed())
                        .toList();

                totalPages = Math.max(1, (int) Math.ceil((double) mobList.size() / limit));
                page = Math.max(1, Math.min(page, totalPages));
                report.add("<#FCD472><bold>=== Mob Kill Statistics ===</bold>");
                report.add("<#6C757D>Page " + page + " of " + totalPages + " (" + mobList.size() + " total)");

                mobList.stream()
                        .skip((long) (page - 1) * limit)
                        .limit(limit)
                        .forEach(e -> {
                            var s = e.getValue();
                            double dropRate = (s.eligibleKills > 0) ? (s.totalDropped * 100.0 / s.eligibleKills) : 0.0;
                            double spawnerPct = (s.totalKilled > 0) ? (s.spawnerKills * 100.0 / s.totalKilled) : 0.0;
                            report.add("<#FCD472>" + FormatUtils.formatEnumName(e.getKey()));
                            report.add("  <#6C757D>Killed: <#f2ebd7>" + String.format("%,d", s.totalKilled)
                                    + " <#6C757D>| Eligible: <#f2ebd7>" + String.format("%,d", s.eligibleKills)
                                    + " <#6C757D>| Dropped: <#f2ebd7>" + String.format("%,d", s.totalDropped)
                                    + " <#6C757D>(<#f2ebd7>" + FormatUtils.formatStatPercent(dropRate) + "<#6C757D>)");
                            if (s.spawnerKills > 0) {
                                report.add("  <#6C757D>Spawner kills: <#f2ebd7>" + String.format("%,d", s.spawnerKills)
                                        + " <#6C757D>(<#f2ebd7>" + FormatUtils.formatStatPercent(spawnerPct) + "<#6C757D>)");
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

        // Read under lock — runs on main thread
        final Map<UUID, PlayerStats> playerSnapshot;
        synchronized (mu) {
            playerSnapshot = new HashMap<>(playerStats);
        }

        // Find player by name (case-insensitive)
        PlayerStats player = null;
        for (PlayerStats ps : playerSnapshot.values()) {
            if (ps.playerName.equalsIgnoreCase(playerName)) {
                player = ps;
                break;
            }
        }

        if (player == null) {
            return null;
        }

        // Header
        report.add("<#FCD472><bold>=== Player Statistics: " + player.playerName + " ===</bold>");
        if (player.firstSeen != null) {
            report.add("<#6C757D>First tracked: <#f2ebd7>" + player.firstSeen.format(formatter));
        }
        if (player.lastSeen != null) {
            report.add("<#6C757D>Last seen: <#f2ebd7>" + player.lastSeen.format(formatter));
        }
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
        report.add("<#FCD472><bold>Overall Statistics:</bold>");
        report.add("<#6C757D>Total mobs killed: <#f2ebd7>" + String.format("%,d", player.mobsKilled));

        if (player.eligibleKills > 0) {
            double eligiblePct = (player.mobsKilled > 0) ? (player.eligibleKills * 100.0 / player.mobsKilled) : 0.0;
            report.add("<#6C757D>Eligible kills: <#f2ebd7>" + String.format("%,d", player.eligibleKills) +
                    " <#6C757D>(<#f2ebd7>" + FormatUtils.formatStatPercent(eligiblePct) + "<#6C757D>)");
        }

        if (player.spawnerKills > 0) {
            double spawnerPct = (player.mobsKilled > 0) ? (player.spawnerKills * 100.0 / player.mobsKilled) : 0.0;
            report.add("<#6C757D>Spawner kills: <#f2ebd7>" + String.format("%,d", player.spawnerKills) +
                    " <#6C757D>(<#f2ebd7>" + FormatUtils.formatStatPercent(spawnerPct) + "<#6C757D>)");
        }

        report.add("<#6C757D>Total drops received: <#f2ebd7>" + String.format("%,d", player.dropsReceived));

        double ratio = (player.mobsKilled > 0) ? (player.dropsReceived * 100.0 / player.mobsKilled) : 0.0;
        report.add("<#6C757D>Drop ratio: <#f2ebd7>" + FormatUtils.formatStatPercent(ratio));

        if (player.rarestDropName != null) {
            report.add("<#6C757D>Rarest drop: <#8FAA87><drop_" + player.rarestDropName +
                    "> <#6C757D>(<#f2ebd7>" + FormatUtils.formatStatPercent(player.rarestDropChance * 100) + "<#6C757D>)");
        }

        // Show favorites
        if (favoriteMob != null) {
            report.add("<#6C757D>Favorite mob: <#D89B6A>" + favoriteMob +
                    " <#6C757D>(<#f2ebd7>" + String.format("%,d", favoriteMobKills) + " kills<#6C757D>)");
        }
        if (favoriteDrop != null) {
            report.add("<#6C757D>Most common drop: <#8FAA87><drop_" + favoriteDrop +
                    "> <#6C757D>(<#f2ebd7>" + String.format("%,d", favoriteDropCount) + " times<#6C757D>)");
        }

        report.add("");

        // Kills by mob type
        if (player.mobKillCounts != null && !player.mobKillCounts.isEmpty()) {
            report.add("<#FCD472><bold>Kills by Mob Type:</bold>");
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
                        line.append("  <#6C757D>• <#D89B6A>").append(FormatUtils.formatEnumName(mobType)).append(": ");
                        line.append("<#f2ebd7>").append(String.format("%,d", totalKills));

                        // Show details if available
                        if (eligibleKills > 0 || spawnerKills > 0) {
                            line.append(" <#6C757D>(");
                            if (eligibleKills > 0) {
                                line.append("<#f2ebd7>").append(String.format("%,d", eligibleKills)).append(" eligible");
                            }
                            if (spawnerKills > 0) {
                                if (eligibleKills > 0) line.append("<#6C757D>, ");
                                line.append("<#f2ebd7>").append(String.format("%,d", spawnerKills)).append(" spawner");
                            }
                            line.append("<#6C757D>)");
                        }

                        report.add(line.toString());
                    });
            report.add("");
        } else if (player.mobKillCounts == null) {
            report.add("<#6C757D><i>(Detailed mob kill breakdown not available for this player - stats tracked from plugin update onwards)</i>");
            report.add("");
        }

        // Drops received
        if (player.dropCounts != null && !player.dropCounts.isEmpty()) {
            report.add("<#FCD472><bold>Drops Received:</bold>");
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
                        report.add("  <#6C757D>• <#8FAA87><drop_" + dropId + "> <#6C757D>from <#D89B6A>" + mobType + ": " +
                                "<#f2ebd7>" + String.format("%,d", e.getValue()) + " " +
                                "<#6C757D>(<#f2ebd7>" + FormatUtils.formatStatPercent(pct) + "<#6C757D>)");
                    });

            if (totalUniqueDrops > 15) {
                report.add("  <#6C757D><i>... and " + (totalUniqueDrops - 15) + " more</i>");
            }
        } else if (player.dropCounts == null) {
            report.add("<#6C757D><i>(Detailed drop breakdown not available for this player - stats tracked from plugin update onwards)</i>");
        }

        return report;
    }

    /**
     * Enforce max player stats limit by removing least active players.
     * Called internally when tracking new player data.
     * Must be called while holding the {@code mu} lock.
     */
    private void enforcePlayerStatsLimit() {
        if (maxPlayerStats <= 0 || playerStats.size() <= maxPlayerStats) {
            return;
        }

        // Remove players who haven't been seen recently (oldest first)
        int toRemove = playerStats.size() - maxPlayerStats;
        List<UUID> removed = playerStats.entrySet().stream()
                .sorted((a, b) -> {
                    LocalDateTime aTime = a.getValue().lastSeen != null ? a.getValue().lastSeen : LocalDateTime.MIN;
                    LocalDateTime bTime = b.getValue().lastSeen != null ? b.getValue().lastSeen : LocalDateTime.MIN;
                    return aTime.compareTo(bTime);
                })
                .limit(toRemove)
                .map(Map.Entry::getKey)
                .toList();

        for (UUID uuid : removed) {
            playerStats.remove(uuid);
            dirtyPlayers.remove(uuid);
            deletedPlayers.add(uuid);
        }
    }

    /**
     * Prune old player data based on inactivity period.
     * @param daysInactive number of days of inactivity before pruning
     */
    public void pruneOldPlayers(int daysInactive) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysInactive);
        synchronized (mu) {
            Iterator<Map.Entry<UUID, PlayerStats>> it = playerStats.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, PlayerStats> entry = it.next();
                PlayerStats stats = entry.getValue();
                if (stats.lastSeen != null && stats.lastSeen.isBefore(cutoff)) {
                    UUID uuid = entry.getKey();
                    it.remove();
                    dirtyPlayers.remove(uuid);
                    deletedPlayers.add(uuid);
                }
            }
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

    /**
     * Create a backup of the statistics before resetting.
     * @return the backup file name, or null if backup failed
     */
    public String backupBeforeReset() {
        save();

        String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String extension = "json".equals(dataStore.getTypeName()) ? ".json" : ".db";
        String backupName = "statistics-backup-" + timestamp + extension;
        File backupFile = new File(plugin.getDataFolder(), backupName);

        if (dataStore.backupTo(backupFile)) {
            plugin.getLogger().info("Statistics backed up to: " + backupName);
            return backupName;
        } else {
            plugin.getLogger().warning("Failed to backup statistics");
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
            dirtyMobs.clear();
            dirtyDrops.clear();
            dirtyPlayers.clear();
            dirtyGlobals = false;
            deletedPlayers.clear();
        }
        dataStore.deleteAllStats();
        dataStore.saveGlobalStats(0, 0, 0, trackingStarted);
    }

    /**
     * Swap the data store (used during migration).
     * Dirty flags are preserved — any data recorded between the migration's save()
     * and this swap is still in the live maps and still marked dirty, so the next
     * autosave flush will write it to the new store. No data is lost.
     * @param newStore the new storage backend
     */
    public void swapDataStore(StatisticsDataStore newStore) {
        synchronized (mu) {
            this.dataStore = newStore;
        }
    }

    /**
     * Get the current data store.
     */
    public StatisticsDataStore getDataStore() {
        return dataStore;
    }

    // ===== Deep copy helpers =====

    private static MobStats copyMobStats(MobStats s) {
        MobStats c = new MobStats();
        c.totalKilled = s.totalKilled;
        c.eligibleKills = s.eligibleKills;
        c.totalDropped = s.totalDropped;
        c.spawnerKills = s.spawnerKills;
        return c;
    }

    private static ItemDropStats copyItemDropStats(ItemDropStats d) {
        ItemDropStats c = new ItemDropStats();
        c.mobType = d.mobType;
        c.dropId = d.dropId;
        c.attempts = d.attempts;
        c.expected = d.expected;
        c.timesDropped = d.timesDropped;
        c.totalAmount = d.totalAmount;
        c.lastDropped = d.lastDropped; // LocalDateTime is immutable
        return c;
    }

    private static PlayerStats copyPlayerStats(PlayerStats p) {
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
        return c;
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

    // ===== Data classes =====

    public static class MobStats {
        public long totalKilled = 0;
        public long eligibleKills = 0;
        public long totalDropped = 0;
        public long spawnerKills = 0;
    }

    public static class ItemDropStats {
        public String mobType;
        public String dropId;
        public long attempts = 0;
        public double expected = 0.0;
        public long timesDropped = 0;
        public long totalAmount = 0;
        public LocalDateTime lastDropped;

        public ItemDropStats() {}
        public ItemDropStats(String mobType, String dropId) {
            this.mobType = mobType;
            this.dropId = dropId;
        }
    }

    public static class PlayerStats {
        public String playerName;
        public long mobsKilled = 0;
        public long eligibleKills = 0;
        public long spawnerKills = 0;
        public long dropsReceived = 0;
        public double rarestDropChance = 1.0;
        public String rarestDropName;
        public LocalDateTime firstSeen;
        public LocalDateTime lastSeen;
        public Map<String, Long> mobKillCounts = new HashMap<>();
        public Map<String, Long> mobEligibleKills = new HashMap<>();
        public Map<String, Long> mobSpawnerKills = new HashMap<>();
        public Map<String, Long> dropCounts = new HashMap<>();

        public PlayerStats() {}
        public PlayerStats(String name) {
            this.playerName = name;
            this.firstSeen = LocalDateTime.now();
        }
    }

    public static class StatsSaveData {
        public Map<String, MobStats> mobStats = new HashMap<>();
        public Map<String, ItemDropStats> dropStats = new HashMap<>();
        public Map<UUID, PlayerStats> playerStats = new HashMap<>();
        public long totalKills;
        public long totalEligibleKills;
        public long totalDrops;
        public LocalDateTime trackingStarted;
    }

    public enum ReportType {
        OVERVIEW,
        EXPECTED_VS_ACTUAL,
        RAREST_DROPS,
        TOP_FARMERS,
        MOB_BREAKDOWN
    }
}
