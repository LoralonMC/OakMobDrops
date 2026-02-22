package dev.oakheart.oakmobdrops.util;

import dev.oakheart.oakmobdrops.backend.DropBackend;
import dev.oakheart.oakmobdrops.backend.DropRouter;
import dev.oakheart.oakmobdrops.model.DropEntry;
import dev.oakheart.oakmobdrops.model.DropSpec;
import dev.oakheart.oakmobdrops.model.DropType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared item name and tooltip resolution logic.
 * Used by AnnouncementManager and OakMobDropsCommand to avoid duplicating
 * the meta → backend API → translatable → fallback resolution chain.
 */
public final class ItemNameResolver {

    private ItemNameResolver() {}

    /**
     * Resolve the best display name for an item.
     * Priority: meta displayName → meta itemName → backend API → translatable → drop ID fallback.
     *
     * @param item the ItemStack (may be null if creation failed)
     * @param drop the drop entry for backend/ID context
     * @param router the drop router for backend lookups
     * @return the resolved display name component
     */
    public static Component resolveDisplayName(@Nullable ItemStack item, DropEntry drop, DropRouter router) {
        // Try item meta
        if (item != null) {
            var meta = item.getItemMeta();
            if (meta != null) {
                if (meta.displayName() != null) return meta.displayName();
                if (meta.itemName() != null) return meta.itemName();
            }
        }

        // Try backend API lookup
        if (drop.spec() != null && drop.spec().id() != null && drop.spec().type() != DropType.VANILLA) {
            DropBackend backend = router.forType(drop.spec().type());
            if (backend.isPresent()) {
                Component c = backend.getDisplayName(drop.spec().id());
                if (c != null) return c;
            }
        }

        // Try translatable
        if (item != null) {
            try {
                return Component.translatable(item.getType());
            } catch (Throwable ignored) {}
        }

        // Fallback
        if (drop.spec() != null) {
            if (drop.spec().material() != null) {
                return Component.text(FormatUtils.formatEnumName(drop.spec().material().name()));
            }
            if (drop.spec().id() != null && !drop.spec().id().isEmpty()) {
                return Component.text(drop.spec().id());
            }
        }

        return Component.text(drop.id());
    }

    /**
     * Build a hover tooltip for an item (name + lore).
     *
     * @param item the ItemStack
     * @param drop the drop entry for backend/ID context
     * @param router the drop router for backend lookups
     * @return the tooltip component
     */
    public static Component buildTooltip(@Nullable ItemStack item, DropEntry drop, DropRouter router) {
        var lines = new ArrayList<Component>();

        // Title — same resolution as resolveDisplayName but without itemName() fallback for tooltip
        Component title = null;
        if (item != null) {
            var meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                title = meta.displayName();
            }
        }
        if (title == null && drop.spec() != null && drop.spec().id() != null
                && drop.spec().type() != DropType.VANILLA) {
            DropBackend backend = router.forType(drop.spec().type());
            if (backend.isPresent()) {
                title = backend.getDisplayName(drop.spec().id());
            }
        }
        if (title == null && item != null) {
            try {
                title = Component.translatable(item.getType());
            } catch (Throwable ignored) {
                title = Component.text(FormatUtils.formatEnumName(item.getType().name()));
            }
        }
        if (title == null) {
            title = Component.text(drop.id());
        }
        lines.add(title);

        // Lore
        List<Component> lore = resolveLore(item, drop, router);
        if (lore != null && !lore.isEmpty()) {
            lines.addAll(lore);
        }

        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /**
     * Build a Component with resolved display name and hover tooltip for a drop.
     * Convenience method combining resolveDisplayName + buildTooltip.
     *
     * @param drop the drop entry
     * @param router the drop router for backend lookups
     * @return component with display name and hover event, or plain text fallback
     */
    public static Component buildDropComponent(DropEntry drop, DropRouter router) {
        DropBackend backend = router.forType(drop.spec().type());
        if (!backend.isPresent()) {
            return Component.text(drop.id());
        }

        try {
            DropSpec spec = drop.spec().withAmount(1);
            ItemStack item = backend.createItem(spec, null);

            Component displayName = resolveDisplayName(item, drop, router);
            Component tooltip = buildTooltip(item, drop, router);

            // Only add hover if we resolved something beyond the raw ID
            if (item != null) {
                return displayName.hoverEvent(HoverEvent.showText(tooltip));
            }
            return displayName;
        } catch (Exception ignored) {}

        return Component.text(drop.id());
    }

    @Nullable
    private static List<Component> resolveLore(@Nullable ItemStack item, DropEntry drop, DropRouter router) {
        // Try item meta lore
        if (item != null) {
            var meta = item.getItemMeta();
            if (meta != null && meta.lore() != null && !meta.lore().isEmpty()) {
                return meta.lore();
            }
        }

        // Try backend API lore
        if (drop.spec() != null && drop.spec().id() != null && drop.spec().type() != DropType.VANILLA) {
            DropBackend backend = router.forType(drop.spec().type());
            if (backend.isPresent()) {
                return backend.getLore(drop.spec().id());
            }
        }

        return null;
    }
}
