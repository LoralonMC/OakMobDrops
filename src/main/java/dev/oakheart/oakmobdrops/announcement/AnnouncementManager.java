package dev.oakheart.oakmobdrops.announcement;

import dev.oakheart.oakmobdrops.backend.DropRouter;
import dev.oakheart.oakmobdrops.model.DropEntry;
import dev.oakheart.oakmobdrops.util.FormatUtils;
import dev.oakheart.oakmobdrops.util.ItemNameResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manages drop announcements.
 * Uses TagResolver API with MiniMessage {@code <placeholder>} format.
 */
public class AnnouncementManager {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private final DropRouter router;
    private final String globalAnnouncement;
    private final boolean playSound;
    private final Sound dropSound;

    public AnnouncementManager(JavaPlugin plugin, DropRouter router,
                               String globalAnnouncement, boolean playSound,
                               Sound dropSound) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.router = router;
        this.globalAnnouncement = globalAnnouncement;
        this.playSound = playSound;
        this.dropSound = dropSound;
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
        Component displayName = ItemNameResolver.resolveDisplayName(item, drop, router);
        Component tooltip = ItemNameResolver.buildTooltip(item, drop, router);
        Component itemNameComponent = displayName.hoverEvent(HoverEvent.showText(tooltip));

        // Build TagResolver with all placeholders
        TagResolver resolver = TagResolver.resolver(
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("mob", mobName),
                Placeholder.component("item", itemNameComponent),
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("chance", FormatUtils.formatChancePercent(effectiveChance)),
                Placeholder.unparsed("chance_fraction", FormatUtils.formatChanceFraction(effectiveChance))
        );

        Component announcement = miniMessage.deserialize(announcementText, resolver);

        // Broadcast to all online players (sendMessage is fast -- just queues a packet)
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.sendMessage(announcement);
        }

        // Log to console
        String plainText = PlainTextComponentSerializer.plainText().serialize(announcement);
        plugin.getLogger().info(plainText);
    }
}
