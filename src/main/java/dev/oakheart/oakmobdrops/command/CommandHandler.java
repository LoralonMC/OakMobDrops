package dev.oakheart.oakmobdrops.command;

import dev.oakheart.oakmobdrops.DropStatistics;
import dev.oakheart.oakmobdrops.OakMobDrops;
import dev.oakheart.oakmobdrops.backend.DropBackend;
import dev.oakheart.oakmobdrops.model.DropEntry;
import dev.oakheart.oakmobdrops.model.DropSpec;
import dev.oakheart.oakmobdrops.model.MobDropConfig;
import dev.oakheart.oakmobdrops.model.PagedReport;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles all /oakmobdrops command execution and tab completion.
 */
public class CommandHandler implements CommandExecutor, TabCompleter {
    private final OakMobDrops plugin;
    private final MiniMessage miniMessage;

    public CommandHandler(OakMobDrops plugin, MiniMessage miniMessage) {
        this.plugin = plugin;
        this.miniMessage = miniMessage;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("oakmobdrops")) {
            return Collections.emptyList();
        }

        // First argument - subcommands
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("oakmobdrops.reload")) completions.add("reload");
            if (sender.hasPermission("oakmobdrops.stats")) completions.add("stats");
            if (sender.hasPermission("oakmobdrops.test")) completions.add("test");
            if (sender.hasPermission("oakmobdrops.admin")) completions.add("clearstats");
            if (sender.hasPermission("oakmobdrops.use")) completions.add("list");
            return filterStartsWith(completions, args[0]);
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);

        // Stats command completions
        if (subCommand.equals("stats") && sender.hasPermission("oakmobdrops.stats")) {
            if (args.length == 2) {
                return filterStartsWith(Arrays.asList(
                        "overview", "rarest", "mobs", "players", "player", "variance"
                ), args[1]);
            }
            if (args.length == 3) {
                // /omd stats player <name> — suggest online player names
                if (args[1].equalsIgnoreCase("player")) {
                    return filterStartsWith(
                            Bukkit.getOnlinePlayers().stream()
                                    .map(Player::getName)
                                    .sorted(String.CASE_INSENSITIVE_ORDER)
                                    .toList(),
                            args[2]
                    );
                }
                // All other report types — suggest page numbers
                return filterStartsWith(Arrays.asList("1", "2", "3", "4", "5"), args[2]);
            }
        }

        // Test command completions
        if (subCommand.equals("test") && sender.hasPermission("oakmobdrops.test")) {
            if (args.length == 2) {
                Set<String> mobs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (EntityType type : plugin.getMobConfigs().keySet()) {
                    mobs.add(type.name().toLowerCase(Locale.ROOT));
                }
                return filterStartsWith(mobs, args[1]);
            }

            if (args.length == 3) {
                try {
                    EntityType type = EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));
                    MobDropConfig config = plugin.getMobConfigs().get(type);
                    if (config != null) {
                        return filterStartsWith(
                                config.drops().stream().map(DropEntry::id).toList(),
                                args[2]
                        );
                    }
                } catch (IllegalArgumentException ignored) {}
            }

            if (args.length >= 4) {
                // Suggest online players and --full flag
                List<String> suggestions = new ArrayList<>();
                // Check if --full is already used
                boolean hasFullFlag = Arrays.stream(args).anyMatch("--full"::equalsIgnoreCase);
                if (!hasFullFlag) {
                    suggestions.add("--full");
                }
                suggestions.addAll(
                        Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .toList()
                );
                return filterStartsWith(suggestions, args[args.length - 1]);
            }
        }

        // Clearstats command completions
        if (subCommand.equals("clearstats") && sender.hasPermission("oakmobdrops.admin")) {
            if (args.length == 2) {
                return filterStartsWith(List.of("confirm"), args[1]);
            }
        }

        return Collections.emptyList();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("oakmobdrops")) {
            return false;
        }

        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                return handleReload(sender);
            }
            case "stats", "statistics" -> {
                return handleStats(sender, args);
            }
            case "test" -> {
                return handleTest(sender, args);
            }
            case "clearstats" -> {
                return handleClearStats(sender, args);
            }
            case "list" -> {
                return handleList(sender);
            }
            default -> {
                msg(sender, "<red>Unknown subcommand. Use /oakmobdrops for help.</red>");
                return true;
            }
        }
    }

    private void showHelp(CommandSender sender) {
        msg(sender, "<gold><bold>=== OakMobDrops v" + plugin.getPluginMeta().getVersion() + " ===</bold></gold>");
        msg(sender, "<yellow>/omd reload</yellow> <gray>- Reload configuration</gray>");
        msg(sender, "<yellow>/omd stats [type] [page]</yellow> <gray>- View statistics</gray>");
        msg(sender, "<yellow>/omd stats player \\<name\\></yellow> <gray>- View player stats</gray>");
        msg(sender, "<yellow>/omd test \\<mob\\> \\<drop\\> [player] [--full]</yellow> <gray>- Test a drop</gray>");
        msg(sender, "<yellow>/omd clearstats</yellow> <gray>- Reset all statistics</gray>");
        msg(sender, "<yellow>/omd list</yellow> <gray>- Show configured mob drops</gray>");
        msg(sender, "<gray>Configured mobs:</gray> <white>" + plugin.getMobConfigs().size() + "</white>");
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("oakmobdrops.reload")) {
            msg(sender, "<red>You don't have permission to reload the config!</red>");
            return true;
        }

        plugin.reloadConfig();
        plugin.loadConfiguration();
        msg(sender, "<green>Configuration reloaded!</green>");
        return true;
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("oakmobdrops.stats")) {
            msg(sender, "<red>You don't have permission to view statistics!</red>");
            return true;
        }

        DropStatistics statistics = plugin.getStatistics();
        if (statistics == null || !plugin.isStatisticsEnabled()) {
            msg(sender, "<red>Statistics tracking is disabled!</red>");
            return true;
        }

        // Handle per-player stats: /omd stats player <name>
        if (args.length >= 3 && args[1].equalsIgnoreCase("player")) {
            String playerName = args[2];
            List<String> report = statistics.generatePlayerReport(playerName);

            for (String line : report) {
                sender.sendMessage(miniMessage.deserialize(line));
            }
            return true;
        }

        // Handle paginated reports
        DropStatistics.ReportType reportType = DropStatistics.ReportType.OVERVIEW;
        int limit = 5;
        int page = 1;

        if (args.length >= 2) {
            reportType = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "rarest" -> DropStatistics.ReportType.RAREST_DROPS;
                case "players" -> DropStatistics.ReportType.TOP_FARMERS;
                case "mobs" -> DropStatistics.ReportType.MOB_BREAKDOWN;
                case "variance" -> DropStatistics.ReportType.EXPECTED_VS_ACTUAL;
                default -> DropStatistics.ReportType.OVERVIEW;
            };
        }

        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
                page = Math.max(1, page);
            } catch (NumberFormatException ignored) {}
        }

        PagedReport report = statistics.generateReport(reportType, limit, page);

        for (String line : report.lines()) {
            sender.sendMessage(miniMessage.deserialize(line));
        }

        // Render clickable pagination if multi-page
        if (report.totalPages() > 1) {
            String typeArg = switch (report.type()) {
                case RAREST_DROPS -> "rarest";
                case TOP_FARMERS -> "players";
                case MOB_BREAKDOWN -> "mobs";
                case EXPECTED_VS_ACTUAL -> "variance";
                default -> "overview";
            };

            StringBuilder nav = new StringBuilder();
            if (report.currentPage() > 1) {
                nav.append("<click:run_command:'/omd stats ").append(typeArg).append(" ")
                        .append(report.currentPage() - 1).append("'><yellow>[<< Prev]</yellow></click>");
            } else {
                nav.append("<dark_gray>[<< Prev]</dark_gray>");
            }

            nav.append(" <gray>Page ").append(report.currentPage())
                    .append(" of ").append(report.totalPages()).append("</gray> ");

            if (report.currentPage() < report.totalPages()) {
                nav.append("<click:run_command:'/omd stats ").append(typeArg).append(" ")
                        .append(report.currentPage() + 1).append("'><yellow>[Next >>]</yellow></click>");
            } else {
                nav.append("<dark_gray>[Next >>]</dark_gray>");
            }

            sender.sendMessage(miniMessage.deserialize(nav.toString()));
        }

        return true;
    }

    private boolean handleTest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("oakmobdrops.test")) {
            msg(sender, "<red>You don't have permission to test drops!</red>");
            return true;
        }

        if (args.length < 3) {
            msg(sender, "<red>Usage: /omd test \\<mob\\> \\<drop-id\\> [player] [--full]</red>");
            return true;
        }

        try {
            EntityType mobType = EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));
            String dropId = args[2];

            // Parse remaining args: find --full flag and player name
            boolean fullMode = false;
            String playerArg = null;
            for (int i = 3; i < args.length; i++) {
                if (args[i].equalsIgnoreCase("--full")) {
                    fullMode = true;
                } else {
                    playerArg = args[i];
                }
            }

            Player target;
            if (playerArg != null) {
                target = Bukkit.getPlayer(playerArg);
            } else {
                target = (sender instanceof Player p) ? p : null;
            }

            if (target == null) {
                msg(sender, "<red>Player not found!</red>");
                return true;
            }

            MobDropConfig config = plugin.getMobConfigs().get(mobType);
            if (config == null) {
                msg(sender, "<red>No configuration for mob: " + mobType + "</red>");
                return true;
            }

            DropEntry dropEntry = config.drops().stream()
                    .filter(d -> d.id().equalsIgnoreCase(dropId))
                    .findFirst()
                    .orElse(null);

            if (dropEntry == null) {
                msg(sender, "<red>Drop '" + miniMessage.escapeTags(dropId) + "' not found for " + mobType + "</red>");
                return true;
            }

            // Calculate amount
            int amount = dropEntry.minAmount() == dropEntry.maxAmount()
                    ? dropEntry.minAmount()
                    : ThreadLocalRandom.current().nextInt(dropEntry.minAmount(),
                            dropEntry.maxAmount() + 1);

            if (fullMode) {
                // Full pipeline: distribute, announce, stats, commands
                DropSpec specWithAmount = dropEntry.spec().withAmount(amount);
                DropBackend backend = plugin.getRouter().forType(specWithAmount.type());
                ItemStack built = backend.createItem(specWithAmount, dropEntry.dropAtLocation() ? null : target);

                if (built == null) {
                    String backendName = backend.getName();
                    boolean backendAvailable = backend.isPresent();
                    msg(sender, "<red>Failed to build item '" +
                            miniMessage.escapeTags(dropEntry.id()) + "'</red>");
                    msg(sender, "<red>Backend: " + backendName +
                            " (available: " + backendAvailable + ")</red>");
                    msg(sender, "<red>Item ID: " + miniMessage.escapeTags(
                            String.valueOf(specWithAmount.id())) +
                            " | Type: " + specWithAmount.type() + "</red>");
                    if (!backendAvailable) {
                        msg(sender, "<red>The " + backendName +
                                " plugin is not installed or not enabled!</red>");
                    }
                    plugin.getLogger().warning("[Test] Failed to build item '" +
                            dropEntry.id() + "' using " + backendName +
                            " backend (present=" + backendAvailable +
                            ", itemId=" + specWithAmount.id() + ")");
                    return true;
                }

                // Distribute
                if (dropEntry.dropAtLocation()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), built);
                } else {
                    var leftovers = target.getInventory().addItem(built);
                    leftovers.values().forEach(stack ->
                            target.getWorld().dropItemNaturally(target.getLocation(), stack));
                }

                // Announcement
                plugin.getAnnouncementManager().sendAnnouncement(dropEntry, mobType,
                        dropEntry.chance(), target, amount, built);

                // Statistics
                DropStatistics statistics = plugin.getStatistics();
                if (statistics != null && plugin.isStatisticsEnabled()) {
                    statistics.recordAttempt(mobType, dropEntry.id(), dropEntry.chance());
                    statistics.recordSuccess(mobType, dropEntry.id(), amount, dropEntry.chance(), target);
                }

                // Commands
                if (!dropEntry.commands().isEmpty()) {
                    var itemName = built.getItemMeta() != null && built.getItemMeta().displayName() != null
                            ? built.getItemMeta().displayName()
                            : net.kyori.adventure.text.Component.text(built.getType().name());
                    plugin.getCommandExecutor().executeCommands(dropEntry.commands(), target, mobType,
                            itemName, amount, dropEntry.chance());
                }

                msg(sender, "<green>Full test: gave " + dropEntry.id() + " x" + amount +
                        " to " + target.getName() + " (with announcements, stats, commands)</green>");
            } else {
                // Simple mode: just give item
                DropSpec spec = dropEntry.spec().withAmount(amount);
                DropBackend backend = plugin.getRouter().forType(spec.type());
                boolean success = backend.give(target, spec);

                if (success) {
                    msg(sender, "<green>Successfully gave " + dropEntry.id() + " to " + target.getName() + "</green>");
                } else {
                    String backendName = backend.getName();
                    boolean backendAvailable = backend.isPresent();
                    msg(sender, "<red>Failed to give '" +
                            miniMessage.escapeTags(dropEntry.id()) + "'</red>");
                    msg(sender, "<red>Backend: " + backendName +
                            " (available: " + backendAvailable + ")</red>");
                    msg(sender, "<red>Item ID: " + miniMessage.escapeTags(
                            String.valueOf(spec.id())) +
                            " | Type: " + spec.type() + "</red>");
                    if (!backendAvailable) {
                        msg(sender, "<red>The " + backendName +
                                " plugin is not installed or not enabled!</red>");
                    }
                    plugin.getLogger().warning("[Test] Failed to give item '" +
                            dropEntry.id() + "' using " + backendName +
                            " backend (present=" + backendAvailable +
                            ", itemId=" + spec.id() + ")");
                }
            }

        } catch (IllegalArgumentException e) {
            msg(sender, "<red>Invalid mob type: " + miniMessage.escapeTags(args[1]) + "</red>");
        }

        return true;
    }

    private boolean handleClearStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("oakmobdrops.admin")) {
            msg(sender, "<red>You don't have permission!</red>");
            return true;
        }

        DropStatistics statistics = plugin.getStatistics();
        if (statistics == null) {
            msg(sender, "<red>Statistics tracking is disabled!</red>");
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            // Backup before reset
            String backupPath = statistics.backupBeforeReset();
            if (backupPath != null) {
                msg(sender, "<gray>Backup saved to: " + miniMessage.escapeTags(backupPath) + "</gray>");
            }
            statistics.reset();
            msg(sender, "<yellow>Statistics have been reset!</yellow>");
        } else {
            msg(sender, "<red><bold>Warning:</bold> This will permanently clear ALL statistics data!</red>");
            msg(sender, "<yellow>Run <white>/omd clearstats confirm</white> within 30 seconds to confirm.</yellow>");
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("oakmobdrops.use")) {
            msg(sender, "<red>You don't have permission!</red>");
            return true;
        }

        Map<EntityType, MobDropConfig> configs = plugin.getMobConfigs();
        if (configs.isEmpty()) {
            msg(sender, "<yellow>No mob drops configured.</yellow>");
            return true;
        }

        msg(sender, "<gold><bold>=== Configured Mob Drops ===</bold></gold>");

        configs.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .forEach(entry -> {
                    EntityType mobType = entry.getKey();
                    MobDropConfig config = entry.getValue();

                    // Build flags
                    StringBuilder flags = new StringBuilder();
                    if (config.requirePlayerKill()) flags.append(" <gray>[player-kill]</gray>");
                    if (config.allowSpawnerDrops()) flags.append(" <gray>[spawner-ok]</gray>");

                    msg(sender, "<yellow>" + dev.oakheart.oakmobdrops.util.FormatUtils.formatEntityName(mobType) +
                            "</yellow> <dark_gray>(" + config.drops().size() + " drops)</dark_gray>" + flags);

                    for (DropEntry drop : config.drops()) {
                        String amountRange = drop.minAmount() == drop.maxAmount()
                                ? String.valueOf(drop.minAmount())
                                : drop.minAmount() + "-" + drop.maxAmount();
                        msg(sender, "  <gray>-</gray> <aqua>" + drop.id() + "</aqua> " +
                                "<dark_gray>[" + drop.spec().type().name() + "]</dark_gray> " +
                                "<white>" + dev.oakheart.oakmobdrops.util.FormatUtils.formatStatPercent(drop.chance() * 100) +
                                "</white> <gray>x" + amountRange + "</gray>");
                    }
                });

        return true;
    }

    private void msg(CommandSender sender, String miniMessageText) {
        sender.sendMessage(miniMessage.deserialize(miniMessageText));
    }

    private List<String> filterStartsWith(Collection<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>(options);
        }

        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .toList();
    }
}
