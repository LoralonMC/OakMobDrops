package dev.oakheart.oakmobdrops.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Locale;

/**
 * Executes commands with placeholder replacement for drop events.
 * Runs commands as console to support any plugin command.
 */
public class CommandExecutor {
    private final JavaPlugin plugin;
    private final boolean debugMode;

    private static final DecimalFormat PCT = new DecimalFormat("0.####");
    private static final DecimalFormat INT_GROUP = new DecimalFormat("#,###");

    public CommandExecutor(JavaPlugin plugin, boolean debugMode) {
        this.plugin = plugin;
        this.debugMode = debugMode;
    }

    /**
     * Execute a list of commands with placeholder replacement.
     * Commands are run as console on the main thread.
     *
     * @param commands list of command templates
     * @param player the player who received the drop
     * @param mobType the type of mob killed
     * @param itemName display name of the item
     * @param amount amount of items dropped
     * @param chance the drop chance (0.0 to 1.0)
     */
    public void executeCommands(List<String> commands, Player player, EntityType mobType,
                                Component itemName, int amount, double chance) {
        if (commands == null || commands.isEmpty()) {
            return;
        }

        // Convert component to plain text for placeholders
        String itemNamePlain = PlainTextComponentSerializer.plainText().serialize(itemName);
        String playerName = player.getName();
        String mobName = formatEntityName(mobType);
        String chancePercent = formatPercent(chance);
        String chanceFraction = formatChanceFraction(chance);

        for (String commandTemplate : commands) {
            // Replace placeholders (using %% format - universal standard)
            String command = commandTemplate
                    .replace("%player%", playerName)
                    .replace("%mob%", mobName)
                    .replace("%item%", itemNamePlain)
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%chance%", chancePercent)
                    .replace("%chance-fraction%", chanceFraction);

            // Execute on main thread as console
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

                    if (debugMode) {
                        if (success) {
                            plugin.getLogger().info("[Command] Executed: " + command);
                        } else {
                            plugin.getLogger().warning("[Command] Failed: " + command);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[Command] Error executing: " + command);
                    plugin.getLogger().warning("[Command] Error: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Format entity type name to be human-readable.
     * Example: ZOMBIE -> Zombie
     */
    private String formatEntityName(EntityType type) {
        return formatEnumName(type.name());
    }

    /**
     * Format enum name to title case.
     * Example: SKELETON_HORSE -> Skeleton Horse
     */
    private String formatEnumName(String enumName) {
        String[] words = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /**
     * Format probability as percentage.
     * Example: 0.0001 -> "0.01"
     */
    private String formatPercent(double probability0to1) {
        return PCT.format(probability0to1 * 100.0);
    }

    /**
     * Format chance as fraction.
     * Example: 0.0001 -> "1/10,000"
     */
    private String formatChanceFraction(double p) {
        if (!(p > 0.0)) return "Never";
        if (p >= 1.0) return "Always";

        long denominator = (long) Math.ceil(1.0 / p);
        return "1/" + INT_GROUP.format(denominator);
    }
}
