package dev.oakheart.oakmobdrops.listeners;

import dev.oakheart.oakmobdrops.statistics.DropStatistics;
import dev.oakheart.oakmobdrops.OakMobDrops;
import dev.oakheart.oakmobdrops.model.DropEntry;
import dev.oakheart.oakmobdrops.model.MobDropConfig;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
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

import java.util.Comparator;

/**
 * Handles mob-related events: spawner tagging, slime split inheritance, and death drops.
 */
public class MobDropListener implements Listener {
    private final OakMobDrops plugin;
    private final NamespacedKey spawnerMobKey;

    public MobDropListener(OakMobDrops plugin, NamespacedKey spawnerMobKey) {
        this.plugin = plugin;
        this.spawnerMobKey = spawnerMobKey;
    }

    /**
     * Tag mobs spawned from spawners for tracking.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            event.getEntity().getPersistentDataContainer().set(
                    spawnerMobKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );
            if (plugin.isDebugMode()) {
                plugin.getLogger().info("Marked " + event.getEntityType() + " as spawner-spawned");
            }
        }
    }

    /**
     * Inherit spawner tag to slime children when they split.
     */
    @EventHandler(ignoreCancelled = true)
    public void onSlimeSplit(SlimeSplitEvent event) {
        if (!plugin.isInheritSpawnerTag()) {
            return;
        }

        var parent = event.getEntity();
        if (!parent.getPersistentDataContainer().has(spawnerMobKey, PersistentDataType.BYTE)) {
            return;
        }

        // Tag children on next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            var location = parent.getLocation();
            for (var child : parent.getWorld().getNearbyEntitiesByType(
                    org.bukkit.entity.Slime.class, location, 2.0, 2.0, 2.0)) {
                if (child.getTicksLived() <= 1) {
                    child.getPersistentDataContainer().set(spawnerMobKey,
                            PersistentDataType.BYTE, (byte) 1);
                    if (plugin.isDebugMode()) {
                        plugin.getLogger().info("Inherited spawner tag to split slime");
                    }
                }
            }
        });
    }

    /**
     * Handle mob deaths and process drops.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        EntityType entityType = entity.getType();

        // Check if this mob has configured drops
        MobDropConfig config = plugin.getMobConfigs().get(entityType);
        if (config == null) {
            return;
        }

        boolean fromSpawner = entity.getPersistentDataContainer().has(spawnerMobKey,
                PersistentDataType.BYTE);
        Player killer = entity.getKiller();

        // Record kill for statistics (before eligibility checks)
        DropStatistics statistics = plugin.getStatistics();
        boolean statsEnabled = plugin.isStatisticsEnabled();
        if (statistics != null && statsEnabled) {
            statistics.recordKill(entityType, fromSpawner, killer);
        }

        // Apply eligibility checks
        if (fromSpawner && !config.allowSpawnerDrops()) {
            if (plugin.isDebugMode()) {
                plugin.getLogger().info(entityType.name() + " was from spawner and spawner drops are disabled");
            }
            return;
        }

        if (config.requirePlayerKill() && killer == null) {
            if (plugin.isDebugMode()) {
                plugin.getLogger().info(entityType.name() + " died but not killed by player");
            }
            return;
        }

        // Find recipient for drops
        Player recipient = (killer != null) ? killer : findNearestPlayer(entity);
        if (recipient == null) {
            if (plugin.isDebugMode()) {
                plugin.getLogger().info("No player found to receive drops");
            }
            return;
        }

        // Mark as eligible kill
        if (statistics != null && statsEnabled) {
            statistics.recordEligibleKill(entityType, killer);
        }

        // Process all drops
        boolean anyDropOccurred = false;
        for (DropEntry drop : config.drops()) {
            if (plugin.getDropProcessor().processDrop(drop, entity, recipient, killer)) {
                anyDropOccurred = true;
            }
        }

        if (plugin.isDebugMode() && !anyDropOccurred) {
            plugin.getLogger().info("No drops succeeded for " + entityType.name());
        }
    }

    /**
     * Find the nearest player to an entity within the configured search radius.
     * @param entity the entity to search near
     * @return the nearest player, or null if none found
     */
    private Player findNearestPlayer(LivingEntity entity) {
        var location = entity.getLocation();
        return entity.getWorld().getNearbyPlayers(location, plugin.getSearchRadius())
                .stream()
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(location)))
                .orElse(null);
    }
}
