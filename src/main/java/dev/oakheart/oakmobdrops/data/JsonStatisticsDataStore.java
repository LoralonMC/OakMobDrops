package dev.oakheart.oakmobdrops.data;

import com.google.gson.*;
import dev.oakheart.oakmobdrops.statistics.DropStatistics;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Logger;

public class JsonStatisticsDataStore implements StatisticsDataStore {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
            .setPrettyPrinting()
            .create();

    private final File dataFolder;
    private final Logger logger;
    private DropStatistics.StatsSaveData internalData;
    private boolean batching = false;

    public JsonStatisticsDataStore(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.internalData = new DropStatistics.StatsSaveData();
    }

    @Override
    public void initialize() {
        if (!dataFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dataFolder.mkdirs();
        }
    }

    @Override
    public DropStatistics.StatsSaveData loadAll() {
        File statsFile = new File(dataFolder, "statistics.json");
        if (!statsFile.exists()) {
            internalData = new DropStatistics.StatsSaveData();
            return internalData;
        }

        try (Reader reader = Files.newBufferedReader(statsFile.toPath(), StandardCharsets.UTF_8)) {
            DropStatistics.StatsSaveData data = GSON.fromJson(reader, DropStatistics.StatsSaveData.class);
            if (data != null) {
                // Ensure maps are never null
                if (data.mobStats == null) data.mobStats = new HashMap<>();
                if (data.dropStats == null) data.dropStats = new HashMap<>();
                if (data.playerStats == null) data.playerStats = new HashMap<>();
                internalData = data;
                return data;
            }
        } catch (IOException | JsonSyntaxException e) {
            logger.warning("Failed to load statistics: " + e.getMessage());
        }

        internalData = new DropStatistics.StatsSaveData();
        return internalData;
    }

    @Override
    public synchronized void saveAll(DropStatistics.StatsSaveData data) {
        this.internalData = data;
        writeFileIfNotBatching();
    }

    @Override
    public synchronized void saveMobStats(Map<String, DropStatistics.MobStats> dirty) {
        if (internalData.mobStats == null) internalData.mobStats = new HashMap<>();
        internalData.mobStats.putAll(dirty);
        writeFileIfNotBatching();
    }

    @Override
    public synchronized void saveDropStats(Map<String, DropStatistics.ItemDropStats> dirty) {
        if (internalData.dropStats == null) internalData.dropStats = new HashMap<>();
        internalData.dropStats.putAll(dirty);
        writeFileIfNotBatching();
    }

    @Override
    public synchronized void savePlayerStats(Map<UUID, DropStatistics.PlayerStats> dirty) {
        if (internalData.playerStats == null) internalData.playerStats = new HashMap<>();
        internalData.playerStats.putAll(dirty);
        writeFileIfNotBatching();
    }

    @Override
    public synchronized void saveGlobalStats(long totalKills, long totalEligibleKills, long totalDrops,
                                              LocalDateTime trackingStarted) {
        internalData.totalKills = totalKills;
        internalData.totalEligibleKills = totalEligibleKills;
        internalData.totalDrops = totalDrops;
        internalData.trackingStarted = trackingStarted;
        writeFileIfNotBatching();
    }

    @Override
    public synchronized void deletePlayerStats(Collection<UUID> uuids) {
        if (internalData.playerStats != null) {
            for (UUID uuid : uuids) {
                internalData.playerStats.remove(uuid);
            }
            writeFileIfNotBatching();
        }
    }

    @Override
    public synchronized void deleteAllStats() {
        internalData = new DropStatistics.StatsSaveData();
        writeFileIfNotBatching();
    }

    @Override
    public synchronized void beginBatch() {
        batching = true;
    }

    @Override
    public synchronized void endBatch() {
        batching = false;
        writeFile();
    }

    private void writeFileIfNotBatching() {
        if (!batching) {
            writeFile();
        }
    }

    @Override
    public boolean backupTo(File destination) {
        File statsFile = new File(dataFolder, "statistics.json");
        if (!statsFile.exists()) return false;

        try {
            Files.copy(statsFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            logger.warning("Failed to backup statistics: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        // No resources to close for JSON
    }

    @Override
    public String getTypeName() {
        return "json";
    }

    private void writeFile() {
        Path target = new File(dataFolder, "statistics.json").toPath();
        Path tmp = new File(dataFolder, "statistics.json.tmp").toPath();

        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            GSON.toJson(internalData, writer);
            writer.flush();
        } catch (IOException e) {
            logger.warning("Failed to write statistics tmp file: " + e.getMessage());
            return;
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                logger.warning("Failed to move statistics file: " + ex.getMessage());
            }
        } catch (IOException e) {
            logger.warning("Failed to move statistics file: " + e.getMessage());
        }
    }

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
}
