package dev.oakheart.oakmobdrops.hook;

import dev.oakheart.oakmobdrops.statistics.DropStatistics;
import dev.oakheart.oakmobdrops.OakMobDrops;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
            case "total_kills" -> String.valueOf(statistics.getTotalKills());
            case "total_drops" -> String.valueOf(statistics.getTotalDrops());
            case "total_eligible_kills" -> String.valueOf(statistics.getTotalEligibleKills());
            case "player_kills" -> {
                if (player == null) yield "0";
                yield String.valueOf(statistics.getPlayerKills(player.getUniqueId()));
            }
            case "player_drops" -> {
                if (player == null) yield "0";
                yield String.valueOf(statistics.getPlayerDrops(player.getUniqueId()));
            }
            case "player_eligible_kills" -> {
                if (player == null) yield "0";
                yield String.valueOf(statistics.getPlayerEligibleKills(player.getUniqueId()));
            }
            default -> null;
        };
    }
}
