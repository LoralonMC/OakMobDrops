package dev.oakheart.oakmobdrops.data;

import dev.oakheart.oakmobdrops.statistics.DropStatistics;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface StatisticsDataStore {

    void initialize() throws Exception;

    DropStatistics.StatsSaveData loadAll();

    void saveAll(DropStatistics.StatsSaveData data);

    void saveMobStats(Map<String, DropStatistics.MobStats> dirty);

    void saveDropStats(Map<String, DropStatistics.ItemDropStats> dirty);

    void savePlayerStats(Map<UUID, DropStatistics.PlayerStats> dirty);

    void saveGlobalStats(long totalKills, long totalEligibleKills, long totalDrops, LocalDateTime trackingStarted);

    void deletePlayerStats(Collection<UUID> uuids);

    void deleteAllStats();

    /**
     * Begin a batch of writes. Implementations may defer disk I/O until endBatch().
     * Callers must always pair with endBatch() in a finally block.
     */
    default void beginBatch() {}

    /**
     * End a batch of writes and flush any deferred data to disk.
     */
    default void endBatch() {}

    boolean backupTo(File destination);

    void close();

    String getTypeName();
}
