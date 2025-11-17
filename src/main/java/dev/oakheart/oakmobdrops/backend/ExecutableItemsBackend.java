package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.model.DropSpec;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

/**
 * Backend for ExecutableItems plugin.
 * Uses reflection with method caching for better performance.
 */
public class ExecutableItemsBackend implements DropBackend {
    private final JavaPlugin plugin;
    private final boolean debugMode;

    // Cached reflection methods
    private static volatile Method getExecutableItemsManager;
    private static volatile Method getExecutableItem;
    private static volatile Method isPresent;
    private static volatile Method get;
    private static volatile Method buildItem;
    private static volatile boolean reflectionInitialized = false;
    private static volatile boolean reflectionFailed = false;

    public ExecutableItemsBackend(JavaPlugin plugin) {
        this.plugin = plugin;
        this.debugMode = plugin.getConfig().getBoolean("settings.debug", false);
        initializeReflection();
    }

    /**
     * Initialize reflection methods once.
     * Thread-safe lazy initialization.
     */
    private void initializeReflection() {
        if (reflectionInitialized || reflectionFailed) {
            return;
        }

        synchronized (ExecutableItemsBackend.class) {
            if (reflectionInitialized || reflectionFailed) {
                return;
            }

            try {
                Class<?> apiClass = Class.forName("com.ssomar.score.api.executableitems.ExecutableItemsAPI");
                getExecutableItemsManager = apiClass.getMethod("getExecutableItemsManager");

                Object manager = getExecutableItemsManager.invoke(null);
                getExecutableItem = manager.getClass().getMethod("getExecutableItem", String.class);

                // Optional methods
                isPresent = Optional.class.getMethod("isPresent");
                get = Optional.class.getMethod("get");

                reflectionInitialized = true;
                plugin.getLogger().info("[ExecutableItems] Detected API successfully");
            } catch (Exception e) {
                reflectionFailed = true;
                if (debugMode) {
                    plugin.getLogger().fine("[ExecutableItems Backend] Reflection initialization failed: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public boolean isPresent() {
        return plugin.getServer().getPluginManager().isPluginEnabled("ExecutableItems")
                && reflectionInitialized;
    }

    @Override
    public String getName() {
        return "ExecutableItems";
    }

    @Override
    @Nullable
    public ItemStack createItem(DropSpec spec, @Nullable Player creator) {
        return buildItemStack(spec, creator);
    }

    @Override
    public boolean give(Player player, DropSpec spec) {
        ItemStack item = buildItemStack(spec, player);
        if (item == null) {
            return false;
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            leftovers.values().forEach(stack ->
                player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
        return true;
    }

    @Override
    public boolean drop(Location location, DropSpec spec) {
        ItemStack item = buildItemStack(spec, null);
        if (item == null) {
            return false;
        }
        location.getWorld().dropItemNaturally(location, item);
        return true;
    }

    /**
     * Build an ItemStack using ExecutableItems API.
     * @param spec the drop specification
     * @param creator optional player context
     * @return the created ItemStack, or null if failed
     */
    @Nullable
    private ItemStack buildItemStack(DropSpec spec, @Nullable Player creator) {
        if (!isPresent() || spec.getId() == null) {
            return null;
        }

        try {
            Object manager = getExecutableItemsManager.invoke(null);
            Object optional = getExecutableItem.invoke(manager, spec.getId());

            if (optional == null) {
                return null;
            }

            boolean present = (boolean) isPresent.invoke(optional);
            if (!present) {
                if (debugMode) {
                    plugin.getLogger().fine("[ExecutableItems] Item not found: " + spec.getId());
                }
                return null;
            }

            Object executableItem = get.invoke(optional);

            // Get or initialize buildItem method
            if (buildItem == null) {
                buildItem = executableItem.getClass().getMethod("buildItem", int.class,
                        Optional.class, Optional.class);
            }

            Object result = buildItem.invoke(executableItem, Math.max(1, spec.getAmount()),
                    Optional.empty(), Optional.ofNullable(creator));

            return (result instanceof ItemStack) ? (ItemStack) result : null;

        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            if (debugMode) {
                plugin.getLogger().warning("[ExecutableItems] Failed to build item '" + spec.getId() +
                        "': " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
            return null;
        }
    }
}
