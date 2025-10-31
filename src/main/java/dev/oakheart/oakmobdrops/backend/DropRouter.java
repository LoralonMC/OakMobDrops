package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.model.DropType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Routes drop requests to the appropriate backend based on drop type.
 */
public class DropRouter {
    private final DropBackend executableItems;
    private final DropBackend itemsAdder;
    private final DropBackend nexo;
    private final DropBackend vanilla;

    public DropRouter(JavaPlugin plugin) {
        this.executableItems = new ExecutableItemsBackend(plugin);
        this.itemsAdder = new ItemsAdderBackend(plugin);
        this.nexo = new NexoBackend(plugin);
        this.vanilla = new VanillaBackend();
    }

    /**
     * Get the backend for a specific drop type.
     * @param type the drop type
     * @return the corresponding backend
     */
    public DropBackend forType(DropType type) {
        return switch (type) {
            case EXECUTABLE_ITEMS -> executableItems;
            case ITEMSADDER -> itemsAdder;
            case NEXO -> nexo;
            case VANILLA -> vanilla;
        };
    }
}
