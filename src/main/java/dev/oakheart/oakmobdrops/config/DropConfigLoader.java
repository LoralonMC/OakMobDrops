package dev.oakheart.oakmobdrops.config;

import dev.oakheart.oakmobdrops.model.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Handles loading and parsing of drop configurations from config.yml.
 * Supports both list and section formats for drop entries.
 */
public class DropConfigLoader {
    private final JavaPlugin plugin;
    private final boolean debugMode;

    public DropConfigLoader(JavaPlugin plugin, boolean debugMode) {
        this.plugin = plugin;
        this.debugMode = debugMode;
    }

    /**
     * Load all mob drop configurations from the config.
     * @param mobsSection the "mobs" section from config.yml
     * @param globalAllowSpawnerDrops the global spawner drops setting
     * @return map of EntityType to MobDropConfig
     */
    public Map<EntityType, MobDropConfig> loadMobConfigs(ConfigurationSection mobsSection,
                                                          boolean globalAllowSpawnerDrops) {
        Map<EntityType, MobDropConfig> configs = new HashMap<>();

        if (mobsSection == null) {
            plugin.getLogger().warning("No mobs configured in config.yml!");
            return configs;
        }

        for (String mobKey : mobsSection.getKeys(false)) {
            try {
                EntityType entityType = EntityType.valueOf(mobKey.toUpperCase(Locale.ROOT));
                ConfigurationSection mobSection = mobsSection.getConfigurationSection(mobKey);

                if (mobSection == null) {
                    continue;
                }

                if (!mobSection.getBoolean("enabled", true)) {
                    if (debugMode) {
                        plugin.getLogger().info("Skipping disabled mob: " + mobKey);
                    }
                    continue;
                }

                MobDropConfig config = parseMobConfig(entityType, mobSection, globalAllowSpawnerDrops);

                if (config.getDrops().isEmpty()) {
                    plugin.getLogger().warning("No valid drops configured for " + entityType + "; skipping.");
                    continue;
                }

                configs.put(entityType, config);

                if (debugMode) {
                    plugin.getLogger().info("Loaded " + entityType.name() + " with " +
                            config.getDrops().size() + " drops:");
                    for (DropEntry drop : config.getDrops()) {
                        plugin.getLogger().info("  - " + drop.getId() + ": " +
                                (drop.getChance() * 100) + "% chance");
                    }
                }

            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid entity type in config: " + mobKey);
            }
        }

        return configs;
    }

    /**
     * Parse a single mob's configuration.
     */
    private MobDropConfig parseMobConfig(EntityType entityType, ConfigurationSection mobSection,
                                         boolean globalAllowSpawnerDrops) {
        boolean requirePlayerKill = mobSection.getBoolean("require-player-kill", true);
        boolean allowSpawnerDrops = mobSection.getBoolean("allow-spawner-drops", globalAllowSpawnerDrops);
        List<DropEntry> drops = new ArrayList<>();

        // Check for new multi-drop format first
        if (mobSection.contains("drops")) {
            if (mobSection.isList("drops")) {
                // List format
                List<Map<?, ?>> dropsList = mobSection.getMapList("drops");
                for (int i = 0; i < dropsList.size(); i++) {
                    Map<?, ?> dropMap = dropsList.get(i);
                    DropEntry entry = parseDropFromMap(dropMap, entityType, "drop_" + i);
                    drops.add(entry);
                }
            } else if (mobSection.isConfigurationSection("drops")) {
                // Section format with named drops
                ConfigurationSection dropsSection = mobSection.getConfigurationSection("drops");
                if (dropsSection != null) {
                    for (String dropId : dropsSection.getKeys(false)) {
                        ConfigurationSection dropSection = dropsSection.getConfigurationSection(dropId);
                        if (dropSection != null) {
                            drops.add(parseDropEntry(dropSection, entityType, dropId));
                        }
                    }
                }
            }
        } else if (mobSection.contains("drop")) {
            // Legacy single-drop format for backward compatibility
            ConfigurationSection dropSection = mobSection.getConfigurationSection("drop");
            if (dropSection != null) {
                DropEntry entry = parseLegacyDropEntry(mobSection, dropSection, entityType);
                drops.add(entry);
            }
        } else {
            plugin.getLogger().warning("No 'drops' or 'drop' section for " + entityType + "; skipping.");
        }

        return new MobDropConfig(entityType, requirePlayerKill, allowSpawnerDrops, drops);
    }

    /**
     * Parse a drop entry from a map (list format).
     */
    private DropEntry parseDropFromMap(Map<?, ?> map, EntityType type, String defaultId) {
        String id = asString(map.get("id"), defaultId);
        double chance = validateChance(asDouble(map.get("chance"), 0.0001), type.name(), id);

        int minAmount = 1, maxAmount = 1;
        Object amountObj = map.get("amount");
        if (amountObj instanceof String s) {
            int[] range = parseAmountRange(s);
            minAmount = range[0];
            maxAmount = range[1];
        } else if (amountObj instanceof Number n) {
            minAmount = maxAmount = Math.max(1, n.intValue());
        }

        boolean dropAtLocation = asBoolean(map.get("drop-at-location"), true);
        String announcement = asNullableString(map.get("announcement"));
        boolean playSound = asBoolean(map.get("play-sound"), true);

        // Parse commands
        List<String> commands = asStringList(map.get("commands"));

        DropSpec spec = parseDropSpecFromMap(map, type);

        return new DropEntry(id, chance, dropAtLocation, announcement, playSound,
                minAmount, maxAmount, spec, commands);
    }

    /**
     * Parse a drop entry from a configuration section (section format).
     */
    private DropEntry parseDropEntry(ConfigurationSection drop, EntityType type, String dropId) {
        double chance = validateChance(drop.getDouble("chance", 0.0001), type.name(), dropId);

        int minAmount = 1, maxAmount = 1;
        String amountStr = drop.getString("amount");
        if (amountStr != null && amountStr.contains("-")) {
            int[] range = parseAmountRange(amountStr);
            minAmount = range[0];
            maxAmount = range[1];
        } else {
            minAmount = maxAmount = Math.max(1, drop.getInt("amount", 1));
        }

        boolean dropAtLocation = drop.getBoolean("drop-at-location", true);
        String announcement = drop.getString("announcement", null);
        boolean playSound = drop.getBoolean("play-sound", true);

        // Parse commands
        List<String> commands = drop.getStringList("commands");

        DropSpec spec = parseDropSpec(drop, type);

        return new DropEntry(dropId, chance, dropAtLocation, announcement, playSound,
                minAmount, maxAmount, spec, commands);
    }

    /**
     * Parse a legacy drop entry (old format).
     */
    private DropEntry parseLegacyDropEntry(ConfigurationSection mobSection,
                                           ConfigurationSection drop, EntityType type) {
        double chance = validateChance(mobSection.getDouble("drop-chance", 0.0001),
                type.name(), "default");

        boolean dropAtLocation = mobSection.getBoolean("drop-at-location", true);
        String announcement = mobSection.getString("announcement", null);
        int amount = Math.max(1, drop.getInt("amount", 1));

        // Parse commands (legacy format might have commands at mob level)
        List<String> commands = mobSection.getStringList("commands");

        DropSpec spec = parseDropSpec(drop, type);

        return new DropEntry("default", chance, dropAtLocation, announcement, true,
                amount, amount, spec, commands);
    }

    /**
     * Parse amount range string (e.g., "1-3" or "5").
     * @return array [min, max]
     */
    private int[] parseAmountRange(String amountStr) {
        if (amountStr.contains("-")) {
            String[] parts = amountStr.split("-");
            try {
                int min = Math.max(1, Integer.parseInt(parts[0].trim()));
                int max = Math.max(min, Integer.parseInt(parts[1].trim()));
                return new int[]{min, max};
            } catch (NumberFormatException e) {
                return new int[]{1, 1};
            }
        } else {
            try {
                int value = Math.max(1, Integer.parseInt(amountStr.trim()));
                return new int[]{value, value};
            } catch (NumberFormatException e) {
                return new int[]{1, 1};
            }
        }
    }

    /**
     * Parse a DropSpec from a configuration section.
     */
    private DropSpec parseDropSpec(ConfigurationSection drop, EntityType type) {
        DropSpec spec = new DropSpec();

        String typeStr = drop.getString("type", "VANILLA").toUpperCase(Locale.ROOT);
        try {
            spec.setType(DropType.valueOf(typeStr));
        } catch (IllegalArgumentException ex) {
            spec.setType(DropType.VANILLA);
        }

        // Support both "id" and "item-id" for flexibility
        spec.setId(optTrim(drop.getString("item-id", drop.getString("id", null))));

        String mat = optTrim(drop.getString("material", null));
        if (mat != null) {
            try {
                spec.setMaterial(Material.valueOf(mat.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid material '" + mat + "' for " + type + "; ignoring.");
            }
        }

        spec.setName(drop.getString("name", null));
        spec.setLore(drop.getStringList("lore"));

        return spec;
    }

    /**
     * Parse a DropSpec from a map.
     */
    private DropSpec parseDropSpecFromMap(Map<?, ?> map, EntityType type) {
        DropSpec spec = new DropSpec();

        String typeStr = asString(map.get("type"), "VANILLA").toUpperCase(Locale.ROOT);
        try {
            spec.setType(DropType.valueOf(typeStr));
        } catch (IllegalArgumentException e) {
            spec.setType(DropType.VANILLA);
        }

        // Item id key flexibility
        String itemId = asNullableString(map.get("item-id"));
        if (itemId == null) {
            itemId = asNullableString(map.get("itemId"));
        }
        spec.setId(itemId);

        String mat = asNullableString(map.get("material"));
        if (mat != null) {
            try {
                spec.setMaterial(Material.valueOf(mat.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid material '" + mat + "' for " + type + "; ignoring.");
            }
        }

        spec.setName(asNullableString(map.get("name")));
        List<String> lore = asStringList(map.get("lore"));
        if (lore != null) {
            spec.setLore(lore);
        }

        return spec;
    }

    /**
     * Validate and clamp chance value, warn if invalid.
     */
    private double validateChance(double rawChance, String mobName, String dropId) {
        if (rawChance < 0.0 || rawChance > 1.0) {
            plugin.getLogger().warning("Invalid chance " + rawChance + " for " + mobName +
                    ":" + dropId + " (will be clamped to 0-1)");
        }
        return Math.max(0.0, Math.min(1.0, rawChance));
    }

    // Helper methods for type conversion
    private static String asNullableString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private static String asString(Object o, String def) {
        return (o == null) ? def : String.valueOf(o);
    }

    private static boolean asBoolean(Object o, boolean def) {
        return (o instanceof Boolean b) ? b : def;
    }

    private static double asDouble(Object o, double def) {
        return (o instanceof Number n) ? n.doubleValue() : def;
    }

    private static List<String> asStringList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object it : list) {
                out.add(String.valueOf(it));
            }
            return out;
        }
        return null;
    }

    private static String optTrim(String s) {
        return (s == null) ? null : s.trim();
    }
}
