package dev.oakheart.oakmobdrops.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class CommandExecutor {
    private final JavaPlugin plugin;
    private final boolean debugMode;

    public CommandExecutor(JavaPlugin plugin, boolean debugMode) {
        this.plugin = plugin;
        this.debugMode = debugMode;
    }

    public void executeCommands(List<String> commands, Player player, EntityType mobType,
                                Component itemName, int amount, double chance) {
        if (commands == null || commands.isEmpty()) {
            return;
        }

        String itemNamePlain = PlainTextComponentSerializer.plainText().serialize(itemName);
        String playerName = player.getName();
        String mobName = FormatUtils.formatEntityName(mobType);
        String chancePercent = FormatUtils.formatChancePercent(chance);
        String chanceFraction = FormatUtils.formatChanceFraction(chance);

        // Plain string replacement — console commands are plain text, not MiniMessage
        for (String commandTemplate : commands) {
            String command = commandTemplate
                    .replace("<player>", playerName)
                    .replace("<mob>", mobName)
                    .replace("<item>", itemNamePlain)
                    .replace("<amount>", String.valueOf(amount))
                    .replace("<chance>", chancePercent)
                    .replace("<chance_fraction>", chanceFraction);

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
        }
    }
}
