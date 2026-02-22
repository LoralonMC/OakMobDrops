package dev.oakheart.oakmobdrops.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MessageManager {

    private static final String[] MESSAGE_KEYS = {
            "reload-success", "reload-failed",
            "stats-disabled", "invalid-mob-type", "no-config-for-mob",
            "drop-not-found", "player-not-found", "stats-player-not-found",
            "test-success-simple", "test-success-full", "test-build-failed",
            "clearstats-warning", "clearstats-confirmed", "clearstats-backup",
            "clearstats-expired", "no-drops-configured", "only-players",
            "help-header", "help-reload", "help-stats", "help-stats-player",
            "help-test", "help-clearstats", "help-list", "help-configured-mobs",
            "help-migrate",
            "list-header", "list-mob", "list-drop",
            "pagination-prev", "pagination-next", "pagination-page",
            "pagination-prev-disabled", "pagination-next-disabled",
            "migrate-started", "migrate-success", "migrate-failed", "migrate-same-type"
    };

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, String> texts = new HashMap<>();
    private final Map<String, String> displays = new HashMap<>();

    public void load(FileConfiguration config) {
        texts.clear();
        displays.clear();

        for (String key : MESSAGE_KEYS) {
            texts.put(key, config.getString("messages." + key + ".text", ""));
            displays.put(key, config.getString("messages." + key + ".display", "chat"));
        }
    }

    // --- Core send helpers ---

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        parse(key, resolvers).ifPresent(component -> {
            String display = displays.getOrDefault(key, "chat");
            if ("action_bar".equals(display) && sender instanceof Player player) {
                player.sendActionBar(component);
            } else {
                sender.sendMessage(component);
            }
        });
    }

    public Optional<Component> parse(String key, TagResolver... resolvers) {
        String text = texts.get(key);
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(miniMessage.deserialize(text, resolvers));
    }

    /**
     * Get the raw text template for a message key.
     * Used by callers that need to build MiniMessage strings (e.g. pagination).
     */
    public String getText(String key) {
        return texts.getOrDefault(key, "");
    }

    // --- Named convenience methods ---

    public void sendReloadSuccess(CommandSender sender) {
        send(sender, "reload-success");
    }

    public void sendReloadFailed(CommandSender sender) {
        send(sender, "reload-failed");
    }

    public void sendStatsDisabled(CommandSender sender) {
        send(sender, "stats-disabled");
    }

    public void sendInvalidMobType(CommandSender sender, String mob) {
        send(sender, "invalid-mob-type", Placeholder.unparsed("mob", mob));
    }

    public void sendNoConfigForMob(CommandSender sender, String mob) {
        send(sender, "no-config-for-mob", Placeholder.unparsed("mob", mob));
    }

    public void sendDropNotFound(CommandSender sender, String drop, String mob) {
        send(sender, "drop-not-found",
                Placeholder.unparsed("drop", drop),
                Placeholder.unparsed("mob", mob));
    }

    public void sendPlayerNotFound(CommandSender sender) {
        send(sender, "player-not-found");
    }

    public void sendStatsPlayerNotFound(CommandSender sender, String player) {
        send(sender, "stats-player-not-found", Placeholder.unparsed("player", player));
    }

    public void sendTestSuccessSimple(CommandSender sender, Component drop, String player) {
        send(sender, "test-success-simple",
                Placeholder.component("drop", drop),
                Placeholder.unparsed("player", player));
    }

    public void sendTestSuccessFull(CommandSender sender, Component drop, int amount, String player) {
        send(sender, "test-success-full",
                Placeholder.component("drop", drop),
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("player", player));
    }

    public void sendTestBuildFailed(CommandSender sender, String drop, String backend, String available) {
        send(sender, "test-build-failed",
                Placeholder.unparsed("drop", drop),
                Placeholder.unparsed("backend", backend),
                Placeholder.unparsed("available", available));
    }

    public void sendClearstatsWarning(CommandSender sender) {
        send(sender, "clearstats-warning");
    }

    public void sendClearstatsConfirmed(CommandSender sender) {
        send(sender, "clearstats-confirmed");
    }

    public void sendClearstatsBackup(CommandSender sender, String path) {
        send(sender, "clearstats-backup", Placeholder.unparsed("path", path));
    }

    public void sendClearstatsExpired(CommandSender sender) {
        send(sender, "clearstats-expired");
    }

    public void sendNoDropsConfigured(CommandSender sender) {
        send(sender, "no-drops-configured");
    }

    public void sendOnlyPlayers(CommandSender sender) {
        send(sender, "only-players");
    }

    public void sendHelpHeader(CommandSender sender, String version) {
        send(sender, "help-header", Placeholder.unparsed("version", version));
    }

    public void sendHelpReload(CommandSender sender) {
        send(sender, "help-reload");
    }

    public void sendHelpStats(CommandSender sender) {
        send(sender, "help-stats");
    }

    public void sendHelpStatsPlayer(CommandSender sender) {
        send(sender, "help-stats-player");
    }

    public void sendHelpTest(CommandSender sender) {
        send(sender, "help-test");
    }

    public void sendHelpClearstats(CommandSender sender) {
        send(sender, "help-clearstats");
    }

    public void sendHelpList(CommandSender sender) {
        send(sender, "help-list");
    }

    public void sendHelpConfiguredMobs(CommandSender sender, int count) {
        send(sender, "help-configured-mobs", Placeholder.unparsed("count", String.valueOf(count)));
    }

    public void sendListHeader(CommandSender sender) {
        send(sender, "list-header");
    }

    public void sendListMob(CommandSender sender, String mob, String count, Component flags) {
        send(sender, "list-mob",
                Placeholder.unparsed("mob", mob),
                Placeholder.unparsed("count", count),
                Placeholder.component("flags", flags));
    }

    public void sendListDrop(CommandSender sender, Component dropId, String type, String chance, String amount) {
        send(sender, "list-drop",
                Placeholder.component("drop_id", dropId),
                Placeholder.unparsed("type", type),
                Placeholder.unparsed("chance", chance),
                Placeholder.unparsed("amount", amount));
    }

    // --- Migrate ---

    public void sendHelpMigrate(CommandSender sender) {
        send(sender, "help-migrate");
    }

    public void sendMigrateStarted(CommandSender sender, String target) {
        send(sender, "migrate-started", Placeholder.unparsed("target", target));
    }

    public void sendMigrateSuccess(CommandSender sender, String backup) {
        send(sender, "migrate-success", Placeholder.unparsed("backup", backup));
    }

    public void sendMigrateFailed(CommandSender sender, String error) {
        send(sender, "migrate-failed", Placeholder.unparsed("error", error));
    }

    public void sendMigrateSameType(CommandSender sender, String type) {
        send(sender, "migrate-same-type", Placeholder.unparsed("type", type));
    }
}
