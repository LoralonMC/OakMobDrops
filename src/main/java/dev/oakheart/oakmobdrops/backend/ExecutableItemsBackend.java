package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.OakMobDrops;
import dev.oakheart.oakmobdrops.model.DropSpec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import net.kyori.adventure.text.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ExecutableItemsBackend implements DropBackend {
    private final OakMobDrops plugin;

    // Cached reflection methods (instance-level to avoid stale state across reloads)
    private volatile Method getExecutableItemsManager;
    private volatile Method getExecutableItem;
    private volatile Method optionalIsPresent;
    private volatile Method optionalGet;
    private volatile Method buildItem;
    private volatile Method apiGetName;
    private volatile boolean reflectionInitialized = false;
    private volatile boolean reflectionFailed = false;

    public ExecutableItemsBackend(OakMobDrops plugin) {
        this.plugin = plugin;
        initializeReflection();
    }

    /**
     * Initialize reflection methods.
     * Called from constructor -- safe publication is guaranteed by final field in DropRouter.
     */
    private void initializeReflection() {
        try {
            Class<?> apiClass = Class.forName("com.ssomar.score.api.executableitems.ExecutableItemsAPI");
            getExecutableItemsManager = apiClass.getMethod("getExecutableItemsManager");

            Object manager = getExecutableItemsManager.invoke(null);
            getExecutableItem = manager.getClass().getMethod("getExecutableItem", String.class);

            // Optional methods
            optionalIsPresent = Optional.class.getMethod("isPresent");
            optionalGet = Optional.class.getMethod("get");

            reflectionInitialized = true;
            plugin.getLogger().info("[ExecutableItems] Detected API successfully");
        } catch (Exception e) {
            reflectionFailed = true;
            if (plugin.isDebugMode()) {
                plugin.getLogger().fine("[ExecutableItems Backend] Reflection initialization failed: " + e.getMessage());
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

    /**
     * Build an ItemStack using ExecutableItems API.
     * @param spec the drop specification
     * @param creator optional player context
     * @return the created ItemStack, or null if failed
     */
    @Nullable
    private ItemStack buildItemStack(DropSpec spec, @Nullable Player creator) {
        if (!isPresent() || spec.id() == null) {
            return null;
        }

        try {
            Object manager = getExecutableItemsManager.invoke(null);
            Object optional = getExecutableItem.invoke(manager, spec.id());

            if (optional == null) {
                return null;
            }

            boolean present = (boolean) optionalIsPresent.invoke(optional);
            if (!present) {
                if (plugin.isDebugMode()) {
                    plugin.getLogger().fine("[ExecutableItems] Item not found: " + spec.id());
                }
                return null;
            }

            Object executableItem = optionalGet.invoke(optional);

            // Get or initialize buildItem method
            if (buildItem == null) {
                buildItem = executableItem.getClass().getMethod("buildItem", int.class,
                        Optional.class, Optional.class);
            }

            Object result = buildItem.invoke(executableItem, Math.max(1, spec.amount()),
                    Optional.empty(), Optional.ofNullable(creator));

            return (result instanceof ItemStack) ? (ItemStack) result : null;

        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.getLogger().warning("[ExecutableItems] Failed to build item '" + spec.id() +
                        "': " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Resolve the ExecutableItem object for a given item ID.
     * Shared by getDisplayName/getLore to avoid duplicating reflection logic.
     */
    @Nullable
    private Object resolveExecutableItem(String itemId) {
        if (!isPresent() || itemId == null) {
            return null;
        }
        try {
            Object manager = getExecutableItemsManager.invoke(null);
            Object optional = getExecutableItem.invoke(manager, itemId);
            if (optional == null) return null;
            boolean present = (boolean) optionalIsPresent.invoke(optional);
            if (!present) return null;
            return optionalGet.invoke(optional);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lazily discover and cache the name method on ExecutableItem objects.
     */
    private void discoverNameMethod(Object executableItem) {
        if (apiGetName != null) return;
        for (String methodName : new String[]{"getName", "getDisplayName", "getItemName"}) {
            try {
                apiGetName = executableItem.getClass().getMethod(methodName);
                if (plugin.isDebugMode()) {
                    plugin.getLogger().fine("[ExecutableItems] Discovered name method: " + methodName);
                }
                return;
            } catch (NoSuchMethodException ignored) {}
        }
    }

    /**
     * Get display name for an ExecutableItems item.
     * Tries the EI API name method first, then falls back to building the item.
     */
    @Override
    @Nullable
    public Component getDisplayName(String itemId) {
        Object executableItem = resolveExecutableItem(itemId);
        if (executableItem == null) return null;

        // Try direct API name method
        discoverNameMethod(executableItem);
        if (apiGetName != null) {
            try {
                Object result = apiGetName.invoke(executableItem);
                if (result instanceof Component c) {
                    return c;
                } else if (result instanceof String s && !s.isEmpty()) {
                    return Component.text(s);
                }
            } catch (Exception ignored) {}
        }

        // Fallback: build item and check meta
        try {
            if (buildItem == null) {
                buildItem = executableItem.getClass().getMethod("buildItem", int.class,
                        Optional.class, Optional.class);
            }
            Object result = buildItem.invoke(executableItem, 1, Optional.empty(), Optional.empty());
            if (result instanceof ItemStack item) {
                var meta = item.getItemMeta();
                if (meta != null) {
                    if (meta.displayName() != null) return meta.displayName();
                    if (meta.itemName() != null) return meta.itemName();
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Get lore for an ExecutableItems item by building a preview item.
     */
    @Override
    @Nullable
    public List<Component> getLore(String itemId) {
        Object executableItem = resolveExecutableItem(itemId);
        if (executableItem == null) return null;

        try {
            if (buildItem == null) {
                buildItem = executableItem.getClass().getMethod("buildItem", int.class,
                        Optional.class, Optional.class);
            }
            Object result = buildItem.invoke(executableItem, 1, Optional.empty(), Optional.empty());
            if (result instanceof ItemStack item) {
                var meta = item.getItemMeta();
                if (meta != null && meta.lore() != null && !meta.lore().isEmpty()) {
                    return meta.lore();
                }
            }
        } catch (Exception ignored) {}

        return null;
    }
}
