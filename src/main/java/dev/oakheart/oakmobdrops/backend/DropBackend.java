package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.model.DropSpec;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

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
     * Give an item directly to a player.
     * @param player the player to receive the item
     * @param spec the item specification
     * @return true if the item was successfully given
     */
    boolean give(Player player, DropSpec spec);

    /**
     * Drop an item at a location.
     * @param location the location to drop the item
     * @param spec the item specification
     * @return true if the item was successfully dropped
     */
    boolean drop(Location location, DropSpec spec);

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
}
