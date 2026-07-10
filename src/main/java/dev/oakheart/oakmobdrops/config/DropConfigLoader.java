package dev.oakheart.oakmobdrops.config;

import dev.oakheart.oakmobdrops.model.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class DropConfigLoader {
    private final JavaPlugin plugin;
    private final boolean debugMode;

    public DropConfigLoader(JavaPlugin plugin, boolean debugMode) {
        this.plugin = plugin;
        this.debugMode = debugMode;
    }

    public Map<EntityType, MobDropConfig> loadMobConfigs(ConfigurationSection mobsSection,
                                                          boolean globalAllowSpawnerDrops) {
        Map<EntityType, MobDropConfig> configs = new HashMap<>();

        if (mobsSection == null) {
            plugin.getLogger().warning("No mobs configured in config.yml!");
            return configs;
        }

        for (String mobKey : mobsSection.getKeys(false)) {
            ConfigurationSection mobSection = mobsSection.getConfigurationSection(mobKey);
            if (mobSection == null) continue;

            try {
                EntityType entityType = EntityType.valueOf(mobKey.toUpperCase(Locale.ROOT));

                if (!mobSection.getBoolean("enabled", true)) {
                    if (debugMode) {
                        plugin.getLogger().info("Skipping disabled mob: " + mobKey);
                    }
                    continue;
                }

                MobDropConfig config = parseMobConfig(entityType, mobSection, globalAllowSpawnerDrops);

                if (config.drops().isEmpty()) {
                    plugin.getLogger().warning("No valid drops configured for " + entityType + "; skipping.");
                    continue;
                }

                configs.put(entityType, config);

                if (debugMode) {
                    plugin.getLogger().info("Loaded " + entityType.name() + " with " +
                            config.drops().size() + " drops:");
                    for (DropEntry drop : config.drops()) {
                        plugin.getLogger().info("  - " + drop.id() + ": " +
                                (drop.chance() * 100) + "% chance");
                    }
                }

            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid entity type in config: " + mobKey);
            }
        }

        return configs;
    }

    private MobDropConfig parseMobConfig(EntityType entityType, ConfigurationSection mobSection,
                                         boolean globalAllowSpawnerDrops) {
        boolean requirePlayerKill = mobSection.getBoolean("require-player-kill", true);
        boolean allowSpawnerDrops = mobSection.getBoolean("allow-spawner-drops", globalAllowSpawnerDrops);
        List<DropEntry> drops = new ArrayList<>();

        // Try list format first (YAML sequence: drops: [{...}, ...])
        List<Map<?, ?>> mapList = mobSection.getMapList("drops");
        if (!mapList.isEmpty()) {
            for (int i = 0; i < mapList.size(); i++) {
                Map<?, ?> dropMap = mapList.get(i);
                DropEntry entry = parseDropFromMap(dropMap, entityType, "drop_" + i);
                if (entry != null) {
                    drops.add(entry);
                }
            }
        } else {
            // Try section format (YAML mapping: drops: { named_drop: {...} })
            ConfigurationSection dropsSection = mobSection.getConfigurationSection("drops");
            if (dropsSection != null) {
                for (String dropId : dropsSection.getKeys(false)) {
                    ConfigurationSection dropSection = dropsSection.getConfigurationSection(dropId);
                    if (dropSection != null) {
                        DropEntry entry = parseDropEntry(dropSection, entityType, dropId);
                        if (entry != null) {
                            drops.add(entry);
                        }
                    }
                }
            } else {
                // Legacy single-drop format (drop: {...})
                ConfigurationSection dropSection = mobSection.getConfigurationSection("drop");
                if (dropSection != null) {
                    DropEntry entry = parseLegacyDropEntry(mobSection, dropSection, entityType);
                    if (entry != null) {
                        drops.add(entry);
                    }
                } else {
                    plugin.getLogger().warning("No 'drops' or 'drop' section for " + entityType + "; skipping.");
                }
            }
        }

        return new MobDropConfig(entityType, requirePlayerKill, allowSpawnerDrops, drops);
    }

    private DropEntry parseDropFromMap(Map<?, ?> map, EntityType type, String defaultId) {
        String id = getMapString(map, "id", defaultId);
        double chance = validateChance(getMapDouble(map, "chance", 0.0001), type.name(), id);

        int minAmount = 1, maxAmount = 1;
        Object amountObj = map.get("amount");
        if (amountObj instanceof String amountStr && amountStr.contains("-")) {
            int[] range = parseAmountRange(amountStr);
            minAmount = range[0];
            maxAmount = range[1];
        } else {
            int amountInt = getMapInt(map, "amount", 1);
            minAmount = maxAmount = Math.max(1, amountInt);
        }

        boolean dropAtLocation = getMapBoolean(map, "drop-at-location", true);
        String announcement = getNullableMapString(map, "announcement");
        boolean playSound = getMapBoolean(map, "play-sound", true);

        List<String> commands = getMapStringList(map, "commands");

        DropSpec spec = parseDropSpecFromMap(map, type);
        if (spec == null) return null;

        return new DropEntry(id, chance, dropAtLocation, announcement, playSound,
                minAmount, maxAmount, spec, commands);
    }

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
        String announcement = getNullableString(drop, "announcement");
        boolean playSound = drop.getBoolean("play-sound", true);

        List<String> commands = drop.getStringList("commands");

        DropSpec spec = parseDropSpec(drop, type);
        if (spec == null) return null;

        return new DropEntry(dropId, chance, dropAtLocation, announcement, playSound,
                minAmount, maxAmount, spec, commands);
    }

    private DropEntry parseLegacyDropEntry(ConfigurationSection mobSection,
                                           ConfigurationSection drop, EntityType type) {
        double chance = validateChance(mobSection.getDouble("drop-chance", 0.0001),
                type.name(), "default");

        boolean dropAtLocation = mobSection.getBoolean("drop-at-location", true);
        String announcement = getNullableString(mobSection, "announcement");
        int amount = Math.max(1, drop.getInt("amount", 1));

        List<String> commands = mobSection.getStringList("commands");

        DropSpec spec = parseDropSpec(drop, type);
        if (spec == null) return null;

        return new DropEntry("default", chance, dropAtLocation, announcement, true,
                amount, amount, spec, commands);
    }

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

    private DropSpec parseDropSpec(ConfigurationSection drop, EntityType type) {
        String typeStr = drop.getString("type", "VANILLA").toUpperCase(Locale.ROOT);
        DropType dropType;
        try {
            dropType = DropType.valueOf(typeStr);
        } catch (IllegalArgumentException ex) {
            // Loud: a typo'd type used to silently fall back to VANILLA, turning
            // an EI/Nexo drop into a material-less spec (and, before the
            // VanillaBackend fix, into STONE handed to players).
            plugin.getLogger().warning("Unknown drop type '" + typeStr + "' for " + type
                    + " ; treating as VANILLA. Valid: " + java.util.Arrays.toString(DropType.values()));
            dropType = DropType.VANILLA;
        }

        String id = optTrim(drop.getString("item-id", drop.getString("id")));

        Material material = null;
        String mat = optTrim(drop.getString("material"));
        if (mat != null) {
            try {
                material = Material.valueOf(mat.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid material '" + mat + "' for " + type + "; ignoring.");
            }
        }

        String name = drop.getString("name");
        List<String> lore = drop.getStringList("lore");

        if (dropType == DropType.VANILLA && material == null) {
            plugin.getLogger().warning("VANILLA drop for " + type
                    + " has no (valid) material ; this drop will never build.");
        }

        return new DropSpec(dropType, id, 1, material, name, lore);
    }

    private DropSpec parseDropSpecFromMap(Map<?, ?> map, EntityType type) {
        String typeStr = getMapString(map, "type", "VANILLA").toUpperCase(Locale.ROOT);
        DropType dropType;
        try {
            dropType = DropType.valueOf(typeStr);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown drop type '" + typeStr + "' for " + type
                    + " ; treating as VANILLA. Valid: " + java.util.Arrays.toString(DropType.values()));
            dropType = DropType.VANILLA;
        }

        String itemId = getMapString(map, "item-id", null);
        if (itemId == null) {
            itemId = getMapString(map, "id", null);
        }
        itemId = optTrim(itemId);

        Material material = null;
        String mat = optTrim(getMapString(map, "material", null));
        if (mat != null) {
            try {
                material = Material.valueOf(mat.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid material '" + mat + "' for " + type + "; ignoring.");
            }
        }

        String name = getMapString(map, "name", null);
        List<String> lore = getMapStringList(map, "lore");

        if (dropType == DropType.VANILLA && material == null) {
            plugin.getLogger().warning("VANILLA drop for " + type
                    + " has no (valid) material ; this drop will never build.");
        }

        return new DropSpec(dropType, itemId, 1, material, name, lore);
    }

    private double validateChance(double rawChance, String mobName, String dropId) {
        if (rawChance < 0.0 || rawChance > 1.0) {
            plugin.getLogger().warning("Invalid chance " + rawChance + " for " + mobName +
                    ":" + dropId + " (will be clamped to 0-1)");
        }
        return Math.max(0.0, Math.min(1.0, rawChance));
    }

    private static String getNullableString(ConfigurationSection section, String key) {
        if (!section.contains(key)) return null;
        String value = section.getString(key);
        if (value != null && value.equalsIgnoreCase("null")) return null;
        return value;
    }

    private static String getNullableMapString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        String str = String.valueOf(value);
        if (str.equalsIgnoreCase("null")) return null;
        return str;
    }

    private static String getMapString(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        return String.valueOf(value);
    }

    private static double getMapDouble(Map<?, ?> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    private static int getMapInt(Map<?, ?> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    private static boolean getMapBoolean(Map<?, ?> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    private static List<String> getMapStringList(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) result.add(String.valueOf(item));
            }
            return result;
        }
        return Collections.emptyList();
    }

    private static String optTrim(String s) {
        return (s == null) ? null : s.trim();
    }
}
