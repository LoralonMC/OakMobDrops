package dev.oakheart.oakmobdrops.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Executes commands with placeholder replacement for drop events.
 * Runs commands as console to support any plugin command.
 */
public class CommandExecutor {
    private final JavaPlugin plugin;
    private final boolean debugMode;

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
        String mobName = FormatUtils.formatEntityName(mobType);
        String chancePercent = FormatUtils.formatChancePercent(chance);
        String chanceFraction = FormatUtils.formatChanceFraction(chance);

        for (String commandTemplate : commands) {
            // Replace placeholders
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
}
