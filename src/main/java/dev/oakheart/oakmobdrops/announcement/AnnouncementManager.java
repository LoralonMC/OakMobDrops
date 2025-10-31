package dev.oakheart.oakmobdrops.announcement;

import dev.oakheart.oakmobdrops.backend.ItemsAdderBackend;
import dev.oakheart.oakmobdrops.backend.NexoBackend;
import dev.oakheart.oakmobdrops.model.DropEntry;
import dev.oakheart.oakmobdrops.model.DropType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Manages drop announcements with async broadcasting to prevent lag.
 * Caches plugin item names and lore for better performance.
 */
public class AnnouncementManager {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private final String globalAnnouncement;
    private final boolean playSound;
    private final Sound dropSound;

    // Caches for plugin item names/lore
    private final Map<String, Component> nexoNameCache = new ConcurrentHashMap<>();
    private final Map<String, List<Component>> nexoLoreCache = new ConcurrentHashMap<>();
    private final Map<String, Component> iaNameCache = new ConcurrentHashMap<>();
    private final Map<String, List<Component>> iaLoreCache = new ConcurrentHashMap<>();

    // Formatters
    private static final DecimalFormat PCT = new DecimalFormat("0.####");
    private static final DecimalFormat INT_GROUP = new DecimalFormat("#,###");

    // Backend references for name/lore resolution
    private final NexoBackend nexoBackend;
    private final ItemsAdderBackend itemsAdderBackend;

    public AnnouncementManager(JavaPlugin plugin, NexoBackend nexoBackend,
                               ItemsAdderBackend itemsAdderBackend,
                               String globalAnnouncement, boolean playSound,
                               Sound dropSound) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.nexoBackend = nexoBackend;
        this.itemsAdderBackend = itemsAdderBackend;
        this.globalAnnouncement = globalAnnouncement;
        this.playSound = playSound;
        this.dropSound = dropSound;
    }

    /**
     * Clear all cached names and lore.
     * Should be called on config reload.
     */
    public void clearCaches() {
        nexoNameCache.clear();
        nexoLoreCache.clear();
        iaNameCache.clear();
        iaLoreCache.clear();
    }

    /**
     * Send a drop announcement for a successful drop.
     * Broadcasts asynchronously to prevent lag on large servers.
     *
     * @param drop the drop entry
     * @param entityType the type of mob killed
     * @param effectiveChance the final chance that was used
     * @param player the player who received the drop
     * @param amount the amount dropped
     * @param item the actual ItemStack that was dropped
     */
    public void sendAnnouncement(DropEntry drop, EntityType entityType,
                                 double effectiveChance, Player player,
                                 int amount, ItemStack item) {
        // Check if announcement is needed
        if (drop.getAnnouncement() == null || drop.getAnnouncement().isEmpty()) {
            // Still play sound if needed
            if (drop.isPlaySound() && playSound) {
                player.playSound(player.getLocation(), dropSound, 1.0f, 1.0f);
            }
            return;
        }

        String announcementText = drop.getAnnouncement().equalsIgnoreCase("global")
                ? globalAnnouncement
                : drop.getAnnouncement();

        if (announcementText == null || announcementText.isEmpty()) {
            return;
        }

        // Build announcement on main thread (has access to entity data)
        String mobName = formatEntityName(entityType);
        Component itemNameComponent = getItemName(item, drop)
                .hoverEvent(HoverEvent.showText(buildItemTooltip(item, drop)));

        // Replace simple text placeholders using %% format (same as commands)
        String processedAnnouncement = announcementText
                .replace("%player%", player.getName())
                .replace("%mob%", mobName)
                .replace("%amount%", String.valueOf(amount))
                .replace("%chance%", formatPercent(effectiveChance))
                .replace("%chance-fraction%", formatChanceFraction(effectiveChance))
                // Convert %item% to MiniMessage tag format to preserve hover tooltip
                .replace("%item%", "<item>");

        // Use MiniMessage Placeholder for item component to preserve hover tooltip
        Component announcement = miniMessage.deserialize(
                processedAnnouncement,
                Placeholder.component("item", itemNameComponent)
        );

        // Broadcast asynchronously to prevent lag
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                // Send message back on main thread (required by API)
                Bukkit.getScheduler().runTask(plugin, () ->
                    onlinePlayer.sendMessage(announcement));
            }

            // Log to console
            String plainText = PlainTextComponentSerializer.plainText().serialize(announcement);
            plugin.getLogger().info(plainText);
        });

        // Play sound (main thread)
        if (drop.isPlaySound() && playSound) {
            player.playSound(player.getLocation(), dropSound, 1.0f, 1.0f);
        }
    }

    /**
     * Get the best display name for an item.
     */
    private Component getItemName(ItemStack item, DropEntry drop) {
        // Try item meta first
        var meta = item.getItemMeta();
        if (meta != null) {
            var comp = meta.displayName();
            if (comp != null) {
                return comp;
            }
        }

        // Try plugin-specific resolvers
        if (drop != null && drop.getSpec() != null && drop.getSpec().getId() != null) {
            if (drop.getSpec().getType() == DropType.NEXO) {
                Component c = resolveNexoDisplayName(drop.getSpec().getId());
                if (c != null) return c;
            } else if (drop.getSpec().getType() == DropType.ITEMSADDER) {
                Component c = resolveItemsAdderDisplayName(drop.getSpec().getId());
                if (c != null) return c;
            }
        }

        // Try translatable component
        try {
            return Component.translatable(item.getType());
        } catch (Throwable ignored) {}

        // Fallback to formatted enum name or ID
        if (drop != null && drop.getSpec() != null) {
            if (drop.getSpec().getMaterial() != null) {
                return Component.text(formatEnumName(drop.getSpec().getMaterial().name()));
            }
            if (drop.getSpec().getId() != null && !drop.getSpec().getId().isEmpty()) {
                return Component.text(drop.getSpec().getId());
            }
        }

        return Component.text("custom_item");
    }

    /**
     * Build a tooltip for an item (name + lore).
     */
    private Component buildItemTooltip(ItemStack item, DropEntry drop) {
        var lines = new ArrayList<Component>();
        var meta = item.getItemMeta();

        // Title
        Component title = null;
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            title = meta.displayName();
        } else if (drop != null && drop.getSpec() != null && drop.getSpec().getId() != null) {
            // Plugin-aware fallbacks
            if (drop.getSpec().getType() == DropType.NEXO) {
                title = resolveNexoDisplayName(drop.getSpec().getId());
            } else if (drop.getSpec().getType() == DropType.ITEMSADDER) {
                title = resolveItemsAdderDisplayName(drop.getSpec().getId());
            }
        }

        if (title == null) {
            try {
                title = Component.translatable(item.getType());
            } catch (Throwable ignored) {
                title = Component.text(formatEnumName(item.getType().name()));
            }
        }
        lines.add(title);

        // Lore
        List<Component> lore = Collections.emptyList();
        List<Component> metaLore = (meta != null) ? meta.lore() : null;

        if (metaLore != null && !metaLore.isEmpty()) {
            lore = metaLore;
        } else if (drop != null && drop.getSpec() != null && drop.getSpec().getId() != null) {
            if (drop.getSpec().getType() == DropType.NEXO) {
                lore = resolveNexoLore(drop.getSpec().getId());
            } else if (drop.getSpec().getType() == DropType.ITEMSADDER) {
                lore = resolveItemsAdderLore(drop.getSpec().getId());
            }
        }

        if (!lore.isEmpty()) {
            lines.addAll(lore);
        }

        return Component.join(JoinConfiguration.newlines(), lines);
    }

    // Cached resolvers for Nexo
    private Component resolveNexoDisplayName(String id) {
        if (id == null || nexoBackend == null) return null;
        return nexoNameCache.computeIfAbsent(id, nexoBackend::getDisplayName);
    }

    private List<Component> resolveNexoLore(String id) {
        if (id == null || nexoBackend == null) return Collections.emptyList();
        return nexoLoreCache.computeIfAbsent(id, k -> {
            List<Component> l = nexoBackend.getLore(k);
            return (l == null) ? Collections.emptyList() : l;
        });
    }

    // Cached resolvers for ItemsAdder
    private Component resolveItemsAdderDisplayName(String id) {
        if (id == null || itemsAdderBackend == null) return null;
        return iaNameCache.computeIfAbsent(id, itemsAdderBackend::getDisplayName);
    }

    private List<Component> resolveItemsAdderLore(String id) {
        if (id == null || itemsAdderBackend == null) return Collections.emptyList();
        return iaLoreCache.computeIfAbsent(id, k -> {
            List<Component> l = itemsAdderBackend.getLore(k);
            return (l == null) ? Collections.emptyList() : l;
        });
    }

    // Formatting utilities
    private String formatEntityName(EntityType type) {
        return formatEnumName(type.name());
    }

    private String formatEnumName(String enumName) {
        String[] words = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private String formatPercent(double probability0to1) {
        return PCT.format(probability0to1 * 100.0);
    }

    private String formatChanceFraction(double p) {
        if (!(p > 0.0)) return "Never";
        if (p >= 1.0) return "Always";

        long denominator = (long) Math.ceil(1.0 / p);
        return "1/" + INT_GROUP.format(denominator);
    }
}
