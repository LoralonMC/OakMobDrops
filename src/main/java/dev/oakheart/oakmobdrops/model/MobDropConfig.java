package dev.oakheart.oakmobdrops.model;

import org.bukkit.entity.EntityType;
import java.util.List;

/**
 * Configuration for a specific mob's drop settings.
 * Contains all drop entries and behavioral flags for that mob type.
 */
public record MobDropConfig(EntityType entityType, boolean requirePlayerKill,
                             boolean allowSpawnerDrops, List<DropEntry> drops) {

    public MobDropConfig {
        drops = drops != null ? List.copyOf(drops) : List.of();
    }
}
