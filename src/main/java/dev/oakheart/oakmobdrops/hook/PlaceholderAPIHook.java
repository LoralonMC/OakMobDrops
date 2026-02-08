package dev.oakheart.oakmobdrops.hook;

import dev.oakheart.oakmobdrops.DropStatistics;
import dev.oakheart.oakmobdrops.OakMobDrops;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for OakMobDrops.
 * Provides placeholders for drop statistics.
 *
 * <p>Available placeholders:</p>
 * <ul>
 *   <li>%oakmobdrops_total_kills% - Total mob kills tracked</li>
 *   <li>%oakmobdrops_total_drops% - Total drops given</li>
 *   <li>%oakmobdrops_total_eligible_kills% - Total eligible kills</li>
 *   <li>%oakmobdrops_player_kills% - Player's mob kills</li>
 *   <li>%oakmobdrops_player_drops% - Player's drops received</li>
 *   <li>%oakmobdrops_player_eligible_kills% - Player's eligible kills</li>
 * </ul>
 */
public class PlaceholderAPIHook extends PlaceholderExpansion {
    private final OakMobDrops plugin;

    public PlaceholderAPIHook(OakMobDrops plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "oakmobdrops";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Loralon";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        DropStatistics statistics = plugin.getStatistics();
        if (statistics == null || !plugin.isStatisticsEnabled()) {
            return "0";
        }

        return switch (params.toLowerCase()) {
            case "total_kills" -> String.format("%,d", statistics.getTotalKills());
            case "total_drops" -> String.format("%,d", statistics.getTotalDrops());
            case "total_eligible_kills" -> String.format("%,d", statistics.getTotalEligibleKills());
            case "player_kills" -> {
                if (player == null) yield "0";
                yield String.format("%,d", statistics.getPlayerKills(player.getUniqueId()));
            }
            case "player_drops" -> {
                if (player == null) yield "0";
                yield String.format("%,d", statistics.getPlayerDrops(player.getUniqueId()));
            }
            case "player_eligible_kills" -> {
                if (player == null) yield "0";
                yield String.format("%,d", statistics.getPlayerEligibleKills(player.getUniqueId()));
            }
            default -> null;
        };
    }
}
