package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.model.DropSpec;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Interface for item drop backends.
 * Implementations handle different custom item plugins (ExecutableItems, ItemsAdder, Nexo, Vanilla).
 */
public interface DropBackend {

    /**
     * Check if this backend's plugin is present and enabled.
     * @return true if the backend is available
     */
    boolean isPresent();

    /**
     * Give an item directly to a player. Overflow drops naturally at the player's location.
     * @param player the player to receive the item
     * @param spec the item specification
     * @return true if the item was successfully given
     */
    default boolean give(Player player, DropSpec spec) {
        ItemStack item = createItem(spec, player);
        if (item == null) return false;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(stack ->
                player.getWorld().dropItemNaturally(player.getLocation(), stack));
        return true;
    }

    /**
     * Drop an item at a location.
     * @param location the location to drop the item
     * @param spec the item specification
     * @return true if the item was successfully dropped
     */
    default boolean drop(Location location, DropSpec spec) {
        ItemStack item = createItem(spec, null);
        if (item == null) return false;
        location.getWorld().dropItemNaturally(location, item);
        return true;
    }

    /**
     * Get the backend name for logging.
     * @return the backend name
     */
    String getName();

    /**
     * Create an ItemStack from the specification.
     * Used for announcements and previews.
     *
     * @param spec the item specification
     * @param creator optional player context for item creation
     * @return the created ItemStack, or null if creation failed
     */
    @Nullable
    ItemStack createItem(DropSpec spec, @Nullable Player creator);

    /**
     * Get display name for an item by its ID.
     * Override in backends that support direct name lookups (Nexo, ItemsAdder, ExecutableItems).
     */
    @Nullable
    default Component getDisplayName(String itemId) {
        return null;
    }

    /**
     * Get lore for an item by its ID.
     * Override in backends that support direct lore lookups (Nexo, ItemsAdder, ExecutableItems).
     */
    @Nullable
    default List<Component> getLore(String itemId) {
        return null;
    }
}
