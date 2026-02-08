package dev.oakheart.oakmobdrops.announcement;

import dev.oakheart.oakmobdrops.backend.ItemsAdderBackend;
import dev.oakheart.oakmobdrops.backend.NexoBackend;
import dev.oakheart.oakmobdrops.model.DropEntry;
import dev.oakheart.oakmobdrops.model.DropType;
import dev.oakheart.oakmobdrops.util.FormatUtils;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages drop announcements.
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
        // Play sound if configured (regardless of announcement)
        if (drop.playSound() && playSound && dropSound != null) {
            player.playSound(player.getLocation(), dropSound, 1.0f, 1.0f);
        }

        // Check if announcement is needed
        if (drop.announcement() == null || drop.announcement().isEmpty()) {
            return;
        }

        String announcementText = drop.announcement().equalsIgnoreCase("global")
                ? globalAnnouncement
                : drop.announcement();

        if (announcementText == null || announcementText.isEmpty()) {
            return;
        }

        // Build announcement (already on main thread from EntityDeathEvent)
        String mobName = FormatUtils.formatEntityName(entityType);
        Component itemNameComponent = getItemName(item, drop)
                .hoverEvent(HoverEvent.showText(buildItemTooltip(item, drop)));

        // Replace simple text placeholders
        String processedAnnouncement = announcementText
                .replace("%player%", player.getName())
                .replace("%mob%", mobName)
                .replace("%amount%", String.valueOf(amount))
                .replace("%chance%", FormatUtils.formatChancePercent(effectiveChance))
                .replace("%chance-fraction%", FormatUtils.formatChanceFraction(effectiveChance))
                // Convert %item% to MiniMessage tag format to preserve hover tooltip
                .replace("%item%", "<item>");

        // Use MiniMessage Placeholder for item component to preserve hover tooltip
        Component announcement = miniMessage.deserialize(
                processedAnnouncement,
                Placeholder.component("item", itemNameComponent)
        );

        // Broadcast to all online players (sendMessage is fast — just queues a packet)
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.sendMessage(announcement);
        }

        // Log to console
        String plainText = PlainTextComponentSerializer.plainText().serialize(announcement);
        plugin.getLogger().info(plainText);
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
        if (drop != null && drop.spec() != null && drop.spec().id() != null) {
            if (drop.spec().type() == DropType.NEXO) {
                Component c = resolveNexoDisplayName(drop.spec().id());
                if (c != null) return c;
            } else if (drop.spec().type() == DropType.ITEMSADDER) {
                Component c = resolveItemsAdderDisplayName(drop.spec().id());
                if (c != null) return c;
            }
        }

        // Try translatable component
        try {
            return Component.translatable(item.getType());
        } catch (Throwable ignored) {}

        // Fallback to formatted enum name or ID
        if (drop != null && drop.spec() != null) {
            if (drop.spec().material() != null) {
                return Component.text(FormatUtils.formatEnumName(drop.spec().material().name()));
            }
            if (drop.spec().id() != null && !drop.spec().id().isEmpty()) {
                return Component.text(drop.spec().id());
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
        } else if (drop != null && drop.spec() != null && drop.spec().id() != null) {
            // Plugin-aware fallbacks
            if (drop.spec().type() == DropType.NEXO) {
                title = resolveNexoDisplayName(drop.spec().id());
            } else if (drop.spec().type() == DropType.ITEMSADDER) {
                title = resolveItemsAdderDisplayName(drop.spec().id());
            }
        }

        if (title == null) {
            try {
                title = Component.translatable(item.getType());
            } catch (Throwable ignored) {
                title = Component.text(FormatUtils.formatEnumName(item.getType().name()));
            }
        }
        lines.add(title);

        // Lore
        List<Component> lore = Collections.emptyList();
        List<Component> metaLore = (meta != null) ? meta.lore() : null;

        if (metaLore != null && !metaLore.isEmpty()) {
            lore = metaLore;
        } else if (drop != null && drop.spec() != null && drop.spec().id() != null) {
            if (drop.spec().type() == DropType.NEXO) {
                lore = resolveNexoLore(drop.spec().id());
            } else if (drop.spec().type() == DropType.ITEMSADDER) {
                lore = resolveItemsAdderLore(drop.spec().id());
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
}
