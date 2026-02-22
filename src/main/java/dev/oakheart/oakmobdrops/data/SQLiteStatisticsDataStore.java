package dev.oakheart.oakmobdrops.data;

import dev.oakheart.oakmobdrops.statistics.DropStatistics;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SQLiteStatisticsDataStore implements StatisticsDataStore {

    private final File dbFile;
    private final Logger logger;
    private Connection connection;
    private boolean batching = false;

    // Prepared statements — created once at init, reused throughout
    private PreparedStatement upsertGlobalStat;
    private PreparedStatement upsertMobStat;
    private PreparedStatement upsertDropStat;
    private PreparedStatement upsertPlayerStat;
    private PreparedStatement upsertPlayerMobStat;
    private PreparedStatement upsertPlayerDropCount;
    private PreparedStatement deletePlayerStatStmt;
    private PreparedStatement deletePlayerMobStatsStmt;
    private PreparedStatement deletePlayerDropCountsStmt;

    public SQLiteStatisticsDataStore(File dataFolder, Logger logger) {
        this.dbFile = new File(dataFolder, "statistics.db");
        this.logger = logger;
    }

    @Override
    public synchronized void initialize() throws Exception {
        if (!dbFile.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            dbFile.getParentFile().mkdirs();
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS global_stats (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS mob_stats (
                        mob_type TEXT PRIMARY KEY,
                        total_killed INTEGER NOT NULL DEFAULT 0,
                        eligible_kills INTEGER NOT NULL DEFAULT 0,
                        total_dropped INTEGER NOT NULL DEFAULT 0,
                        spawner_kills INTEGER NOT NULL DEFAULT 0
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS drop_stats (
                        mob_type TEXT NOT NULL,
                        drop_id TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        expected REAL NOT NULL DEFAULT 0.0,
                        times_dropped INTEGER NOT NULL DEFAULT 0,
                        total_amount INTEGER NOT NULL DEFAULT 0,
                        last_dropped TEXT,
                        PRIMARY KEY (mob_type, drop_id)
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        uuid TEXT PRIMARY KEY,
                        player_name TEXT,
                        mobs_killed INTEGER NOT NULL DEFAULT 0,
                        eligible_kills INTEGER NOT NULL DEFAULT 0,
                        spawner_kills INTEGER NOT NULL DEFAULT 0,
                        drops_received INTEGER NOT NULL DEFAULT 0,
                        rarest_drop_chance REAL NOT NULL DEFAULT 1.0,
                        rarest_drop_name TEXT,
                        first_seen TEXT,
                        last_seen TEXT
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_mob_stats (
                        uuid TEXT NOT NULL,
                        mob_type TEXT NOT NULL,
                        kills INTEGER NOT NULL DEFAULT 0,
                        eligible_kills INTEGER NOT NULL DEFAULT 0,
                        spawner_kills INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid, mob_type)
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_drop_counts (
                        uuid TEXT NOT NULL,
                        drop_id TEXT NOT NULL,
                        count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid, drop_id)
                    )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_stats_last_seen ON player_stats(last_seen)");
        }

        prepareStatements();
    }

    private void prepareStatements() throws SQLException {
        upsertGlobalStat = connection.prepareStatement(
                "INSERT OR REPLACE INTO global_stats (key, value) VALUES (?, ?)");
        upsertMobStat = connection.prepareStatement(
                "INSERT OR REPLACE INTO mob_stats (mob_type, total_killed, eligible_kills, total_dropped, spawner_kills) VALUES (?, ?, ?, ?, ?)");
        upsertDropStat = connection.prepareStatement(
                "INSERT OR REPLACE INTO drop_stats (mob_type, drop_id, attempts, expected, times_dropped, total_amount, last_dropped) VALUES (?, ?, ?, ?, ?, ?, ?)");
        upsertPlayerStat = connection.prepareStatement(
                "INSERT OR REPLACE INTO player_stats (uuid, player_name, mobs_killed, eligible_kills, spawner_kills, drops_received, rarest_drop_chance, rarest_drop_name, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        upsertPlayerMobStat = connection.prepareStatement(
                "INSERT OR REPLACE INTO player_mob_stats (uuid, mob_type, kills, eligible_kills, spawner_kills) VALUES (?, ?, ?, ?, ?)");
        upsertPlayerDropCount = connection.prepareStatement(
                "INSERT OR REPLACE INTO player_drop_counts (uuid, drop_id, count) VALUES (?, ?, ?)");
        deletePlayerStatStmt = connection.prepareStatement(
                "DELETE FROM player_stats WHERE uuid = ?");
        deletePlayerMobStatsStmt = connection.prepareStatement(
                "DELETE FROM player_mob_stats WHERE uuid = ?");
        deletePlayerDropCountsStmt = connection.prepareStatement(
                "DELETE FROM player_drop_counts WHERE uuid = ?");
    }

    @Override
    public synchronized DropStatistics.StatsSaveData loadAll() {
        DropStatistics.StatsSaveData data = new DropStatistics.StatsSaveData();

        try {
            // Global stats
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT key, value FROM global_stats")) {
                while (rs.next()) {
                    String key = rs.getString("key");
                    String value = rs.getString("value");
                    switch (key) {
                        case "totalKills" -> data.totalKills = Long.parseLong(value);
                        case "totalEligibleKills" -> data.totalEligibleKills = Long.parseLong(value);
                        case "totalDrops" -> data.totalDrops = Long.parseLong(value);
                        case "trackingStarted" -> data.trackingStarted = LocalDateTime.parse(value);
                    }
                }
            }

            // Mob stats
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM mob_stats")) {
                while (rs.next()) {
                    DropStatistics.MobStats ms = new DropStatistics.MobStats();
                    ms.totalKilled = rs.getLong("total_killed");
                    ms.eligibleKills = rs.getLong("eligible_kills");
                    ms.totalDropped = rs.getLong("total_dropped");
                    ms.spawnerKills = rs.getLong("spawner_kills");
                    data.mobStats.put(rs.getString("mob_type"), ms);
                }
            }

            // Drop stats
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM drop_stats")) {
                while (rs.next()) {
                    DropStatistics.ItemDropStats ds = new DropStatistics.ItemDropStats();
                    ds.mobType = rs.getString("mob_type");
                    ds.dropId = rs.getString("drop_id");
                    ds.attempts = rs.getLong("attempts");
                    ds.expected = rs.getDouble("expected");
                    ds.timesDropped = rs.getLong("times_dropped");
                    ds.totalAmount = rs.getLong("total_amount");
                    String lastDropped = rs.getString("last_dropped");
                    if (lastDropped != null) ds.lastDropped = LocalDateTime.parse(lastDropped);
                    data.dropStats.put(ds.mobType + ":" + ds.dropId, ds);
                }
            }

            // Player stats
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM player_stats")) {
                while (rs.next()) {
                    DropStatistics.PlayerStats ps = new DropStatistics.PlayerStats();
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    ps.playerName = rs.getString("player_name");
                    ps.mobsKilled = rs.getLong("mobs_killed");
                    ps.eligibleKills = rs.getLong("eligible_kills");
                    ps.spawnerKills = rs.getLong("spawner_kills");
                    ps.dropsReceived = rs.getLong("drops_received");
                    ps.rarestDropChance = rs.getDouble("rarest_drop_chance");
                    ps.rarestDropName = rs.getString("rarest_drop_name");
                    String firstSeen = rs.getString("first_seen");
                    if (firstSeen != null) ps.firstSeen = LocalDateTime.parse(firstSeen);
                    String lastSeen = rs.getString("last_seen");
                    if (lastSeen != null) ps.lastSeen = LocalDateTime.parse(lastSeen);
                    ps.mobKillCounts = new HashMap<>();
                    ps.mobEligibleKills = new HashMap<>();
                    ps.mobSpawnerKills = new HashMap<>();
                    ps.dropCounts = new HashMap<>();
                    data.playerStats.put(uuid, ps);
                }
            }

            // Player mob stats
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM player_mob_stats")) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    DropStatistics.PlayerStats ps = data.playerStats.get(uuid);
                    if (ps != null) {
                        String mobType = rs.getString("mob_type");
                        long kills = rs.getLong("kills");
                        long eligible = rs.getLong("eligible_kills");
                        long spawner = rs.getLong("spawner_kills");
                        if (kills > 0) ps.mobKillCounts.put(mobType, kills);
                        if (eligible > 0) ps.mobEligibleKills.put(mobType, eligible);
                        if (spawner > 0) ps.mobSpawnerKills.put(mobType, spawner);
                    }
                }
            }

            // Player drop counts
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM player_drop_counts")) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    DropStatistics.PlayerStats ps = data.playerStats.get(uuid);
                    if (ps != null) {
                        String dropId = rs.getString("drop_id");
                        long count = rs.getLong("count");
                        if (count > 0) ps.dropCounts.put(dropId, count);
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to load statistics from SQLite", e);
        }

        return data;
    }

    @Override
    public synchronized void saveAll(DropStatistics.StatsSaveData data) {
        if (data.mobStats != null && !data.mobStats.isEmpty()) saveMobStats(data.mobStats);
        if (data.dropStats != null && !data.dropStats.isEmpty()) saveDropStats(data.dropStats);
        if (data.playerStats != null && !data.playerStats.isEmpty()) savePlayerStats(data.playerStats);
        saveGlobalStats(data.totalKills, data.totalEligibleKills, data.totalDrops, data.trackingStarted);
    }

    @Override
    public synchronized void saveMobStats(Map<String, DropStatistics.MobStats> dirty) {
        try {
            if (!batching) connection.setAutoCommit(false);
            for (var entry : dirty.entrySet()) {
                DropStatistics.MobStats s = entry.getValue();
                upsertMobStat.setString(1, entry.getKey());
                upsertMobStat.setLong(2, s.totalKilled);
                upsertMobStat.setLong(3, s.eligibleKills);
                upsertMobStat.setLong(4, s.totalDropped);
                upsertMobStat.setLong(5, s.spawnerKills);
                upsertMobStat.addBatch();
            }
            upsertMobStat.executeBatch();
            if (!batching) connection.commit();
        } catch (SQLException e) {
            logger.warning("Failed to save mob stats: " + e.getMessage());
            if (!batching) rollback();
        } finally {
            if (!batching) setAutoCommit(true);
        }
    }

    @Override
    public synchronized void saveDropStats(Map<String, DropStatistics.ItemDropStats> dirty) {
        try {
            if (!batching) connection.setAutoCommit(false);
            for (var entry : dirty.entrySet()) {
                DropStatistics.ItemDropStats d = entry.getValue();
                upsertDropStat.setString(1, d.mobType);
                upsertDropStat.setString(2, d.dropId);
                upsertDropStat.setLong(3, d.attempts);
                upsertDropStat.setDouble(4, d.expected);
                upsertDropStat.setLong(5, d.timesDropped);
                upsertDropStat.setLong(6, d.totalAmount);
                upsertDropStat.setString(7, d.lastDropped != null ? d.lastDropped.toString() : null);
                upsertDropStat.addBatch();
            }
            upsertDropStat.executeBatch();
            if (!batching) connection.commit();
        } catch (SQLException e) {
            logger.warning("Failed to save drop stats: " + e.getMessage());
            if (!batching) rollback();
        } finally {
            if (!batching) setAutoCommit(true);
        }
    }

    @Override
    public synchronized void savePlayerStats(Map<UUID, DropStatistics.PlayerStats> dirty) {
        try {
            if (!batching) connection.setAutoCommit(false);
            for (var entry : dirty.entrySet()) {
                String uuid = entry.getKey().toString();
                DropStatistics.PlayerStats p = entry.getValue();

                // Upsert main player stats
                upsertPlayerStat.setString(1, uuid);
                upsertPlayerStat.setString(2, p.playerName);
                upsertPlayerStat.setLong(3, p.mobsKilled);
                upsertPlayerStat.setLong(4, p.eligibleKills);
                upsertPlayerStat.setLong(5, p.spawnerKills);
                upsertPlayerStat.setLong(6, p.dropsReceived);
                upsertPlayerStat.setDouble(7, p.rarestDropChance);
                upsertPlayerStat.setString(8, p.rarestDropName);
                upsertPlayerStat.setString(9, p.firstSeen != null ? p.firstSeen.toString() : null);
                upsertPlayerStat.setString(10, p.lastSeen != null ? p.lastSeen.toString() : null);
                upsertPlayerStat.addBatch();

                // Delete existing sub-table rows for this player (replace strategy)
                deletePlayerMobStatsStmt.setString(1, uuid);
                deletePlayerMobStatsStmt.addBatch();
                deletePlayerDropCountsStmt.setString(1, uuid);
                deletePlayerDropCountsStmt.addBatch();

                // Collect all mob types across the three maps
                Set<String> allMobTypes = new HashSet<>();
                if (p.mobKillCounts != null) allMobTypes.addAll(p.mobKillCounts.keySet());
                if (p.mobEligibleKills != null) allMobTypes.addAll(p.mobEligibleKills.keySet());
                if (p.mobSpawnerKills != null) allMobTypes.addAll(p.mobSpawnerKills.keySet());

                for (String mobType : allMobTypes) {
                    long kills = p.mobKillCounts != null ? p.mobKillCounts.getOrDefault(mobType, 0L) : 0;
                    long eligible = p.mobEligibleKills != null ? p.mobEligibleKills.getOrDefault(mobType, 0L) : 0;
                    long spawner = p.mobSpawnerKills != null ? p.mobSpawnerKills.getOrDefault(mobType, 0L) : 0;
                    upsertPlayerMobStat.setString(1, uuid);
                    upsertPlayerMobStat.setString(2, mobType);
                    upsertPlayerMobStat.setLong(3, kills);
                    upsertPlayerMobStat.setLong(4, eligible);
                    upsertPlayerMobStat.setLong(5, spawner);
                    upsertPlayerMobStat.addBatch();
                }

                if (p.dropCounts != null) {
                    for (var drop : p.dropCounts.entrySet()) {
                        upsertPlayerDropCount.setString(1, uuid);
                        upsertPlayerDropCount.setString(2, drop.getKey());
                        upsertPlayerDropCount.setLong(3, drop.getValue());
                        upsertPlayerDropCount.addBatch();
                    }
                }
            }

            // Execute in order: delete sub-tables first, then upsert everything
            deletePlayerMobStatsStmt.executeBatch();
            deletePlayerDropCountsStmt.executeBatch();
            upsertPlayerStat.executeBatch();
            upsertPlayerMobStat.executeBatch();
            upsertPlayerDropCount.executeBatch();
            if (!batching) connection.commit();
        } catch (SQLException e) {
            logger.warning("Failed to save player stats: " + e.getMessage());
            if (!batching) rollback();
        } finally {
            if (!batching) setAutoCommit(true);
        }
    }

    @Override
    public synchronized void saveGlobalStats(long totalKills, long totalEligibleKills, long totalDrops,
                                              LocalDateTime trackingStarted) {
        try {
            if (!batching) connection.setAutoCommit(false);
            setGlobal("totalKills", String.valueOf(totalKills));
            setGlobal("totalEligibleKills", String.valueOf(totalEligibleKills));
            setGlobal("totalDrops", String.valueOf(totalDrops));
            setGlobal("trackingStarted", trackingStarted != null ? trackingStarted.toString() : LocalDateTime.now().toString());
            upsertGlobalStat.executeBatch();
            if (!batching) connection.commit();
        } catch (SQLException e) {
            logger.warning("Failed to save global stats: " + e.getMessage());
            if (!batching) rollback();
        } finally {
            if (!batching) setAutoCommit(true);
        }
    }

    private void setGlobal(String key, String value) throws SQLException {
        upsertGlobalStat.setString(1, key);
        upsertGlobalStat.setString(2, value);
        upsertGlobalStat.addBatch();
    }

    @Override
    public synchronized void deletePlayerStats(Collection<UUID> uuids) {
        try {
            if (!batching) connection.setAutoCommit(false);
            for (UUID uuid : uuids) {
                String uuidStr = uuid.toString();
                deletePlayerMobStatsStmt.setString(1, uuidStr);
                deletePlayerMobStatsStmt.addBatch();
                deletePlayerDropCountsStmt.setString(1, uuidStr);
                deletePlayerDropCountsStmt.addBatch();
                deletePlayerStatStmt.setString(1, uuidStr);
                deletePlayerStatStmt.addBatch();
            }
            deletePlayerMobStatsStmt.executeBatch();
            deletePlayerDropCountsStmt.executeBatch();
            deletePlayerStatStmt.executeBatch();
            if (!batching) connection.commit();
        } catch (SQLException e) {
            logger.warning("Failed to delete player stats: " + e.getMessage());
            if (!batching) rollback();
        } finally {
            if (!batching) setAutoCommit(true);
        }
    }

    @Override
    public synchronized void deleteAllStats() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM player_drop_counts");
            stmt.execute("DELETE FROM player_mob_stats");
            stmt.execute("DELETE FROM player_stats");
            stmt.execute("DELETE FROM drop_stats");
            stmt.execute("DELETE FROM mob_stats");
            stmt.execute("DELETE FROM global_stats");
        } catch (SQLException e) {
            logger.warning("Failed to delete all stats: " + e.getMessage());
        }
    }

    @Override
    public synchronized void beginBatch() {
        batching = true;
        setAutoCommit(false);
    }

    @Override
    public synchronized void endBatch() {
        try {
            connection.commit();
        } catch (SQLException e) {
            logger.warning("Failed to commit batch: " + e.getMessage());
            rollback();
        } finally {
            batching = false;
            setAutoCommit(true);
        }
    }

    @Override
    public synchronized boolean backupTo(File destination) {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("VACUUM INTO '" + destination.getAbsolutePath().replace("'", "''") + "'");
            return true;
        } catch (SQLException e) {
            logger.warning("Failed to backup statistics: " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized void close() {
        closeStatement(upsertGlobalStat);
        closeStatement(upsertMobStat);
        closeStatement(upsertDropStat);
        closeStatement(upsertPlayerStat);
        closeStatement(upsertPlayerMobStat);
        closeStatement(upsertPlayerDropCount);
        closeStatement(deletePlayerStatStmt);
        closeStatement(deletePlayerMobStatsStmt);
        closeStatement(deletePlayerDropCountsStmt);

        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    public String getTypeName() {
        return "sqlite";
    }

    private void closeStatement(PreparedStatement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void setAutoCommit(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
        }
    }
}
