package dev.oakheart.oakmobdrops.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.oakmobdrops.data.JsonStatisticsDataStore;
import dev.oakheart.oakmobdrops.data.SQLiteStatisticsDataStore;
import dev.oakheart.oakmobdrops.data.StatisticsDataStore;
import dev.oakheart.oakmobdrops.statistics.DropStatistics;
import dev.oakheart.oakmobdrops.OakMobDrops;
import dev.oakheart.oakmobdrops.backend.DropBackend;
import dev.oakheart.command.CommandRegistrar;
import dev.oakheart.message.MessageManager;
import dev.oakheart.oakmobdrops.model.DropEntry;
import dev.oakheart.oakmobdrops.model.DropSpec;
import dev.oakheart.oakmobdrops.model.MobDropConfig;
import dev.oakheart.oakmobdrops.model.PagedReport;
import dev.oakheart.oakmobdrops.util.FormatUtils;
import dev.oakheart.oakmobdrops.util.ItemNameResolver;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

@SuppressWarnings("UnstableApiUsage")
public class OakMobDropsCommand {

    private static final long CLEARSTATS_TIMEOUT_MS = 30_000;

    private final OakMobDrops plugin;
    private final MiniMessage miniMessage;
    private final MessageManager messageManager;
    private final Map<UUID, Long> clearStatsTimestamps = new ConcurrentHashMap<>();
    private volatile TagResolver cachedDropResolver;

    public OakMobDropsCommand(OakMobDrops plugin, MiniMessage miniMessage, MessageManager messageManager) {
        this.plugin = plugin;
        this.miniMessage = miniMessage;
        this.messageManager = messageManager;
    }

    public void register() {
        CommandRegistrar.register(plugin, buildCommand(), "OakMobDrops management", List.of("omd", "mobdrops"));
    }

    private LiteralCommandNode<CommandSourceStack> buildCommand() {
        return Commands.literal("oakmobdrops")
                .requires(src -> src.getSender().hasPermission("oakmobdrops.use"))
                // Base command - help
                .executes(ctx -> {
                    showHelp(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                // reload
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("oakmobdrops.reload"))
                        .executes(ctx -> {
                            handleReload(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // list
                .then(Commands.literal("list")
                        .requires(src -> src.getSender().hasPermission("oakmobdrops.list"))
                        .executes(ctx -> {
                            handleList(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // stats
                .then(Commands.literal("stats")
                        .requires(src -> src.getSender().hasPermission("oakmobdrops.stats"))
                        // stats (no args) → overview page 1
                        .executes(ctx -> {
                            handleStats(ctx.getSource().getSender(), DropStatistics.ReportType.OVERVIEW, 1);
                            return Command.SINGLE_SUCCESS;
                        })
                        // stats player <name>
                        .then(Commands.literal("player")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String input = builder.getRemainingLowerCase();
                                            for (Player p : Bukkit.getOnlinePlayers()) {
                                                if (p.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                                                    builder.suggest(p.getName());
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            handlePlayerStats(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"));
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        // stats <type> [page]
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemainingLowerCase();
                                    for (String type : List.of("overview", "rarest", "mobs", "players", "variance")) {
                                        if (type.startsWith(input)) {
                                            builder.suggest(type);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    handleStats(ctx.getSource().getSender(), parseReportType(StringArgumentType.getString(ctx, "type")), 1);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            handleStats(ctx.getSource().getSender(),
                                                    parseReportType(StringArgumentType.getString(ctx, "type")),
                                                    IntegerArgumentType.getInteger(ctx, "page"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                // test <mob> <drop> [player] [--full]
                .then(Commands.literal("test")
                        .requires(src -> src.getSender().hasPermission("oakmobdrops.test"))
                        .then(Commands.argument("mob", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemainingLowerCase();
                                    for (EntityType type : plugin.getMobConfigs().keySet()) {
                                        String name = type.name().toLowerCase(Locale.ROOT);
                                        if (name.startsWith(input)) {
                                            builder.suggest(name);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("drop", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String input = builder.getRemainingLowerCase();
                                            try {
                                                EntityType mobType = EntityType.valueOf(StringArgumentType.getString(ctx, "mob").toUpperCase(Locale.ROOT));
                                                MobDropConfig config = plugin.getMobConfigs().get(mobType);
                                                if (config != null) {
                                                    for (DropEntry drop : config.drops()) {
                                                        if (drop.id().toLowerCase(Locale.ROOT).startsWith(input)) {
                                                            builder.suggest(drop.id());
                                                        }
                                                    }
                                                }
                                            } catch (IllegalArgumentException ignored) {}
                                            return builder.buildFuture();
                                        })
                                        // test <mob> <drop> (simple, self)
                                        .executes(ctx -> handleTest(ctx, null, false))
                                        // test <mob> <drop> --full (full, self)
                                        .then(Commands.literal("--full")
                                                .executes(ctx -> handleTest(ctx, null, true)))
                                        // test <mob> <drop> <player> [--full]
                                        .then(Commands.argument("player", ArgumentTypes.player())
                                                .executes(ctx -> handleTest(ctx, resolvePlayer(ctx), false))
                                                .then(Commands.literal("--full")
                                                        .executes(ctx -> handleTest(ctx, resolvePlayer(ctx), true)))))))
                // clearstats [confirm]
                .then(Commands.literal("clearstats")
                        .requires(src -> src.getSender().hasPermission("oakmobdrops.clearstats"))
                        .executes(ctx -> {
                            handleClearStats(ctx.getSource().getSender(), false);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("confirm")
                                .executes(ctx -> {
                                    handleClearStats(ctx.getSource().getSender(), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                // migrate <json|sqlite>
                .then(Commands.literal("migrate")
                        .requires(src -> src.getSender().hasPermission("oakmobdrops.migrate"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemainingLowerCase();
                                    for (String type : List.of("json", "sqlite")) {
                                        if (type.startsWith(input)) {
                                            builder.suggest(type);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    handleMigrate(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "target").toLowerCase(Locale.ROOT));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();
    }

    private Player resolvePlayer(CommandContext<CommandSourceStack> ctx) {
        try {
            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
            List<Player> players = resolver.resolve(ctx.getSource());
            return players.isEmpty() ? null : players.getFirst();
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Help =====

    private void showHelp(CommandSender sender) {
        messageManager.sendCommand(sender, "help-header",
                Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()));
        if (sender.hasPermission("oakmobdrops.reload")) {
            messageManager.sendCommand(sender, "help-reload");
        }
        if (sender.hasPermission("oakmobdrops.stats")) {
            messageManager.sendCommand(sender, "help-stats");
            messageManager.sendCommand(sender, "help-stats-player");
        }
        if (sender.hasPermission("oakmobdrops.test")) {
            messageManager.sendCommand(sender, "help-test");
        }
        if (sender.hasPermission("oakmobdrops.clearstats")) {
            messageManager.sendCommand(sender, "help-clearstats");
        }
        if (sender.hasPermission("oakmobdrops.list")) {
            messageManager.sendCommand(sender, "help-list");
        }
        if (sender.hasPermission("oakmobdrops.migrate")) {
            messageManager.sendCommand(sender, "help-migrate");
        }
        messageManager.sendCommand(sender, "help-configured-mobs",
                Placeholder.unparsed("count", String.valueOf(plugin.getMobConfigs().size())));
    }

    // ===== Reload =====

    private void handleReload(CommandSender sender) {
        try {
            boolean success = plugin.getConfigManager().reload();
            if (success) {
                plugin.loadConfiguration();
                invalidateDropResolverCache();
                messageManager.sendCommand(sender, "reload-success");
            } else {
                messageManager.sendCommand(sender, "reload-failed");
            }
        } catch (Exception e) {
            messageManager.sendCommand(sender, "reload-failed");
            plugin.getLogger().warning("Reload error: " + e.getMessage());
        }
    }

    // ===== Stats =====

    private DropStatistics.ReportType parseReportType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "rarest" -> DropStatistics.ReportType.RAREST_DROPS;
            case "players" -> DropStatistics.ReportType.TOP_FARMERS;
            case "mobs" -> DropStatistics.ReportType.MOB_BREAKDOWN;
            case "variance" -> DropStatistics.ReportType.EXPECTED_VS_ACTUAL;
            default -> DropStatistics.ReportType.OVERVIEW;
        };
    }

    private void handleStats(CommandSender sender, DropStatistics.ReportType reportType, int page) {
        DropStatistics statistics = plugin.getStatistics();
        if (statistics == null || !plugin.isStatisticsEnabled()) {
            messageManager.sendCommand(sender, "stats-disabled");
            return;
        }

        int limit = 5;
        PagedReport report = statistics.generateReport(reportType, limit, page);

        TagResolver dropResolver = getDropResolver();
        for (String line : report.lines()) {
            sender.sendMessage(miniMessage.deserialize(line, dropResolver));
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
                String prevText = messageManager.getConfig().getString("commands.pagination-prev", "");
                nav.append("<click:run_command:'/omd stats ").append(typeArg).append(" ")
                        .append(report.currentPage() - 1).append("'>")
                        .append(prevText).append("</click>");
            } else {
                nav.append(messageManager.getConfig().getString("commands.pagination-prev-disabled", ""));
            }

            nav.append(" ");
            String pageTemplate = messageManager.getConfig().getString("commands.pagination-page", "");
            if (!pageTemplate.isEmpty()) {
                Component pageComponent = MiniMessage.miniMessage().deserialize(pageTemplate,
                        Placeholder.unparsed("current", String.valueOf(report.currentPage())),
                        Placeholder.unparsed("total", String.valueOf(report.totalPages())));
                nav.append(MiniMessage.miniMessage().serialize(pageComponent));
            }
            nav.append(" ");

            if (report.currentPage() < report.totalPages()) {
                String nextText = messageManager.getConfig().getString("commands.pagination-next", "");
                nav.append("<click:run_command:'/omd stats ").append(typeArg).append(" ")
                        .append(report.currentPage() + 1).append("'>")
                        .append(nextText).append("</click>");
            } else {
                nav.append(messageManager.getConfig().getString("commands.pagination-next-disabled", ""));
            }

            sender.sendMessage(miniMessage.deserialize(nav.toString()));
        }
    }

    private void handlePlayerStats(CommandSender sender, String playerName) {
        DropStatistics statistics = plugin.getStatistics();
        if (statistics == null || !plugin.isStatisticsEnabled()) {
            messageManager.sendCommand(sender, "stats-disabled");
            return;
        }

        List<String> report = statistics.generatePlayerReport(playerName);
        if (report == null) {
            messageManager.sendCommand(sender, "stats-player-not-found",
                    Placeholder.unparsed("player", playerName));
            return;
        }

        TagResolver dropResolver = getDropResolver();
        for (String line : report) {
            sender.sendMessage(miniMessage.deserialize(line, dropResolver));
        }
    }

    // ===== Test =====

    private int handleTest(CommandContext<CommandSourceStack> ctx, Player target, boolean fullMode) {
        CommandSender sender = ctx.getSource().getSender();

        String mobArg = StringArgumentType.getString(ctx, "mob");
        String dropId = StringArgumentType.getString(ctx, "drop");

        // If no target specified, use sender
        if (target == null) {
            if (sender instanceof Player p) {
                target = p;
            } else {
                messageManager.sendCommand(sender, "only-players");
                return Command.SINGLE_SUCCESS;
            }
        }

        EntityType mobType;
        try {
            mobType = EntityType.valueOf(mobArg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            messageManager.sendCommand(sender, "invalid-mob-type",
                    Placeholder.unparsed("mob", mobArg));
            return Command.SINGLE_SUCCESS;
        }

        MobDropConfig config = plugin.getMobConfigs().get(mobType);
        if (config == null) {
            messageManager.sendCommand(sender, "no-config-for-mob",
                    Placeholder.unparsed("mob", mobType.name()));
            return Command.SINGLE_SUCCESS;
        }

        DropEntry dropEntry = config.drops().stream()
                .filter(d -> d.id().equalsIgnoreCase(dropId))
                .findFirst()
                .orElse(null);

        if (dropEntry == null) {
            messageManager.sendCommand(sender, "drop-not-found",
                    Placeholder.unparsed("drop", dropId),
                    Placeholder.unparsed("mob", mobType.name()));
            return Command.SINGLE_SUCCESS;
        }

        // Calculate amount
        int amount = dropEntry.minAmount() == dropEntry.maxAmount()
                ? dropEntry.minAmount()
                : ThreadLocalRandom.current().nextInt(dropEntry.minAmount(), dropEntry.maxAmount() + 1);

        if (fullMode) {
            executeFullTest(sender, target, mobType, dropEntry, amount);
        } else {
            executeSimpleTest(sender, target, dropEntry, amount);
        }

        return Command.SINGLE_SUCCESS;
    }

    private void executeFullTest(CommandSender sender, Player target, EntityType mobType, DropEntry dropEntry, int amount) {
        DropSpec specWithAmount = dropEntry.spec().withAmount(amount);
        DropBackend backend = plugin.getRouter().forType(specWithAmount.type());
        ItemStack built = backend.createItem(specWithAmount, dropEntry.dropAtLocation() ? null : target);

        if (built == null) {
            messageManager.sendCommand(sender, "test-build-failed",
                    Placeholder.unparsed("drop", dropEntry.id()),
                    Placeholder.unparsed("backend", backend.getName()),
                    Placeholder.unparsed("available", backend.isPresent() ? "yes" : "no"));
            plugin.getLogger().warning("[Test] Failed to process item '" + dropEntry.id() +
                    "' using " + backend.getName() + " backend (present=" + backend.isPresent() +
                    ", itemId=" + specWithAmount.id() + ")");
            return;
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

        messageManager.sendCommand(sender, "test-success-full",
                Placeholder.component("drop", buildDropComponent(dropEntry)),
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("player", target.getName()));
    }

    private void executeSimpleTest(CommandSender sender, Player target, DropEntry dropEntry, int amount) {
        DropSpec spec = dropEntry.spec().withAmount(amount);
        DropBackend backend = plugin.getRouter().forType(spec.type());
        boolean success = backend.give(target, spec);

        if (success) {
            messageManager.sendCommand(sender, "test-success-simple",
                    Placeholder.component("drop", buildDropComponent(dropEntry)),
                    Placeholder.unparsed("player", target.getName()));
        } else {
            messageManager.sendCommand(sender, "test-build-failed",
                    Placeholder.unparsed("drop", dropEntry.id()),
                    Placeholder.unparsed("backend", backend.getName()),
                    Placeholder.unparsed("available", backend.isPresent() ? "yes" : "no"));
            plugin.getLogger().warning("[Test] Failed to process item '" + dropEntry.id() +
                    "' using " + backend.getName() + " backend (present=" + backend.isPresent() +
                    ", itemId=" + spec.id() + ")");
        }
    }

    // ===== ClearStats =====

    private void handleClearStats(CommandSender sender, boolean confirmed) {
        DropStatistics statistics = plugin.getStatistics();
        if (statistics == null) {
            messageManager.sendCommand(sender, "stats-disabled");
            return;
        }

        // Purge expired entries to prevent unbounded growth
        long now = System.currentTimeMillis();
        clearStatsTimestamps.values().removeIf(ts -> now - ts > CLEARSTATS_TIMEOUT_MS);

        if (confirmed) {
            // Validate timeout — must have requested within 30 seconds
            if (sender instanceof Player player) {
                Long timestamp = clearStatsTimestamps.remove(player.getUniqueId());
                if (timestamp == null) {
                    messageManager.sendCommand(sender, "clearstats-expired");
                    return;
                }
            }

            String backupPath = statistics.backupBeforeReset();
            if (backupPath != null) {
                messageManager.sendCommand(sender, "clearstats-backup",
                        Placeholder.unparsed("path", backupPath));
            }
            statistics.reset();
            messageManager.sendCommand(sender, "clearstats-confirmed");
        } else {
            // Record timestamp for timeout validation
            if (sender instanceof Player player) {
                clearStatsTimestamps.put(player.getUniqueId(), now);
            }
            messageManager.sendCommand(sender, "clearstats-warning");
        }
    }

    // ===== Migrate =====

    private void handleMigrate(CommandSender sender, String targetType) {
        if (!"json".equals(targetType) && !"sqlite".equals(targetType)) {
            messageManager.sendCommand(sender, "migrate-failed",
                    Placeholder.unparsed("error", "Invalid type. Use 'json' or 'sqlite'."));
            return;
        }

        DropStatistics statistics = plugin.getStatistics();
        if (statistics == null || !plugin.isStatisticsEnabled()) {
            messageManager.sendCommand(sender, "stats-disabled");
            return;
        }

        String currentType = plugin.getConfigManager().getStorageType();
        if (currentType.equals(targetType)) {
            messageManager.sendCommand(sender, "migrate-same-type",
                    Placeholder.unparsed("type", targetType));
            return;
        }

        messageManager.sendCommand(sender, "migrate-started",
                Placeholder.unparsed("target", targetType));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Flush all current data to disk
                statistics.save();

                // Load all from current store
                DropStatistics.StatsSaveData allData = statistics.getDataStore().loadAll();

                // Create and initialize new store
                StatisticsDataStore newStore;
                if ("sqlite".equals(targetType)) {
                    newStore = new SQLiteStatisticsDataStore(plugin.getDataFolder(), plugin.getLogger());
                } else {
                    newStore = new JsonStatisticsDataStore(plugin.getDataFolder(), plugin.getLogger());
                }
                newStore.initialize();

                // Write all data to new store
                newStore.saveAll(allData);

                // Close old store and rename old file
                StatisticsDataStore oldStore = statistics.getDataStore();
                oldStore.close();

                String oldFileName = "json".equals(currentType) ? "statistics.json" : "statistics.db";
                File oldFile = new File(plugin.getDataFolder(), oldFileName);
                String migratedName = oldFileName + ".migrated";
                if (oldFile.exists()) {
                    File migrated = new File(plugin.getDataFolder(), migratedName);
                    //noinspection ResultOfMethodCallIgnored
                    oldFile.renameTo(migrated);
                }

                // Swap data store in DropStatistics
                statistics.swapDataStore(newStore);

                // Update config on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getConfigManager().setStorageType(targetType);
                    messageManager.sendCommand(sender, "migrate-success",
                            Placeholder.unparsed("backup", migratedName));
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Migration failed", e);
                Bukkit.getScheduler().runTask(plugin, () ->
                        messageManager.sendCommand(sender, "migrate-failed",
                                Placeholder.unparsed("error", e.getMessage())));
            }
        });
    }

    // ===== List =====

    private void handleList(CommandSender sender) {
        Map<EntityType, MobDropConfig> configs = plugin.getMobConfigs();
        if (configs.isEmpty()) {
            messageManager.sendCommand(sender, "no-drops-configured");
            return;
        }

        messageManager.sendCommand(sender, "list-header");

        configs.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .forEach(entry -> {
                    EntityType mobType = entry.getKey();
                    MobDropConfig config = entry.getValue();

                    Component flags = Component.empty();
                    if (config.requirePlayerKill()) {
                        flags = flags.append(miniMessage.deserialize(" <#6C757D>[player-kill]"));
                    }
                    if (config.allowSpawnerDrops()) {
                        flags = flags.append(miniMessage.deserialize(" <#6C757D>[spawner-ok]"));
                    }

                    messageManager.sendCommand(sender, "list-mob",
                            Placeholder.unparsed("mob", FormatUtils.formatEntityName(mobType)),
                            Placeholder.unparsed("count", String.valueOf(config.drops().size())),
                            Placeholder.component("flags", flags));

                    for (DropEntry drop : config.drops()) {
                        String amountRange = drop.minAmount() == drop.maxAmount()
                                ? String.valueOf(drop.minAmount())
                                : drop.minAmount() + "-" + drop.maxAmount();
                        messageManager.sendCommand(sender, "list-drop",
                                Placeholder.component("drop_id", buildDropComponent(drop)),
                                Placeholder.unparsed("type", drop.spec().type().name()),
                                Placeholder.unparsed("chance", FormatUtils.formatStatPercent(drop.chance() * 100)),
                                Placeholder.unparsed("amount", amountRange));
                    }
                });
    }

    /**
     * Invalidate the cached drop resolver. Called after config reload.
     */
    public void invalidateDropResolverCache() {
        cachedDropResolver = null;
    }

    /**
     * Get a TagResolver that maps drop_<id> placeholders to resolved item Components.
     * Cached and rebuilt lazily after config reload.
     * Benign race: two threads may both build the resolver; result is idempotent.
     */
    private TagResolver getDropResolver() {
        TagResolver resolver = cachedDropResolver;
        if (resolver != null) {
            return resolver;
        }
        resolver = buildDropResolver();
        cachedDropResolver = resolver;
        return resolver;
    }

    /**
     * Build a TagResolver that maps drop_<id> placeholders to resolved item Components.
     * Used when deserializing stats report lines.
     */
    private TagResolver buildDropResolver() {
        TagResolver.Builder builder = TagResolver.builder();
        for (MobDropConfig config : plugin.getMobConfigs().values()) {
            for (DropEntry drop : config.drops()) {
                builder.resolver(Placeholder.component("drop_" + drop.id(), buildDropComponent(drop)));
            }
        }
        // Fallback for drops no longer in config (e.g., old stats data) —
        // show the raw drop ID instead of literal <drop_xyz> tag text
        builder.resolver(new TagResolver() {
            @Override
            public Tag resolve(String name, ArgumentQueue arguments, Context ctx) {
                if (name.startsWith("drop_")) {
                    return Tag.inserting(Component.text(name.substring(5)));
                }
                return null;
            }

            @Override
            public boolean has(String name) {
                return name.startsWith("drop_");
            }
        });
        return builder.build();
    }

    /**
     * Build a Component for a drop with resolved item name and hover tooltip.
     * Delegates to shared ItemNameResolver utility.
     */
    private Component buildDropComponent(DropEntry drop) {
        return ItemNameResolver.buildDropComponent(drop, plugin.getRouter());
    }

}
