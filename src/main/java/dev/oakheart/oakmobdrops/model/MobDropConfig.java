package dev.oakheart.oakmobdrops.model;

import org.bukkit.entity.EntityType;
import java.util.List;

/**
 * Configuration for a specific mob's drop settings.
 * Contains all drop entries and behavioral flags for that mob type.
 */
public class MobDropConfig {
    private EntityType entityType;
    private boolean requirePlayerKill;
    private boolean allowSpawnerDrops;
    private List<DropEntry> drops;

    public MobDropConfig(EntityType entityType, boolean requirePlayerKill,
                         boolean allowSpawnerDrops, List<DropEntry> drops) {
        this.entityType = entityType;
        this.requirePlayerKill = requirePlayerKill;
        this.allowSpawnerDrops = allowSpawnerDrops;
        this.drops = drops;
    }

    public EntityType getEntityType() {
        return entityType;
    }

    public boolean isRequirePlayerKill() {
        return requirePlayerKill;
    }

    public boolean isAllowSpawnerDrops() {
        return allowSpawnerDrops;
    }

    public List<DropEntry> getDrops() {
        return drops;
    }
}
