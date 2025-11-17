package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.model.DropSpec;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backend for ItemsAdder plugin.
 * Uses reflection with method caching for better performance.
 */
public class ItemsAdderBackend implements DropBackend {
    private final JavaPlugin plugin;
    private final boolean debugMode;

    // Cached reflection methods
    private static volatile Method getInstance;
    private static volatile Method getItemStack;
    private static volatile Method getDisplayName;
    private static volatile Method getLore;
    private static volatile boolean reflectionInitialized = false;
    private static volatile boolean reflectionFailed = false;

    public ItemsAdderBackend(JavaPlugin plugin) {
        this.plugin = plugin;
        this.debugMode = plugin.getConfig().getBoolean("settings.debug", false);
        initializeReflection();
    }

    /**
     * Initialize reflection methods once.
     */
    private void initializeReflection() {
        if (reflectionInitialized || reflectionFailed) {
            return;
        }

        synchronized (ItemsAdderBackend.class) {
            if (reflectionInitialized || reflectionFailed) {
                return;
            }

            try {
                Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                getInstance = customStackClass.getMethod("getInstance", String.class);
                getItemStack = customStackClass.getMethod("getItemStack");

                // Try to get display name method (may vary by version)
                String displayNameMethod = "unknown";
                try {
                    getDisplayName = customStackClass.getMethod("getDisplayName");
                    displayNameMethod = "getDisplayName";
                } catch (NoSuchMethodException e) {
                    try {
                        getDisplayName = customStackClass.getMethod("displayName");
                        displayNameMethod = "displayName";
                    } catch (NoSuchMethodException ignored) {}
                }

                // Try to get lore method (may vary by version)
                String loreMethod = "unknown";
                try {
                    getLore = customStackClass.getMethod("getLore");
                    loreMethod = "getLore";
                } catch (NoSuchMethodException e) {
                    try {
                        getLore = customStackClass.getMethod("lore");
                        loreMethod = "lore";
                    } catch (NoSuchMethodException ignored) {}
                }

                reflectionInitialized = true;
                plugin.getLogger().info("[ItemsAdder] Detected API (methods: " +
                    displayNameMethod + "/" + loreMethod + ")");
            } catch (Exception e) {
                reflectionFailed = true;
                if (debugMode) {
                    plugin.getLogger().fine("[ItemsAdder Backend] Reflection initialization failed: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public boolean isPresent() {
        return plugin.getServer().getPluginManager().isPluginEnabled("ItemsAdder")
                && reflectionInitialized;
    }

    @Override
    public String getName() {
        return "ItemsAdder";
    }

    @Override
    @Nullable
    public ItemStack createItem(DropSpec spec, @Nullable Player creator) {
        return buildItemStack(spec);
    }

    @Override
    public boolean give(Player player, DropSpec spec) {
        ItemStack item = buildItemStack(spec);
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
        ItemStack item = buildItemStack(spec);
        if (item == null) {
            return false;
        }
        location.getWorld().dropItemNaturally(location, item);
        return true;
    }

    /**
     * Build an ItemStack using ItemsAdder API.
     * @param spec the drop specification
     * @return the created ItemStack, or null if failed
     */
    @Nullable
    private ItemStack buildItemStack(DropSpec spec) {
        if (!isPresent() || spec.getId() == null) {
            return null;
        }

        try {
            Object customStack = getInstance.invoke(null, spec.getId());
            if (customStack == null) {
                if (debugMode) {
                    plugin.getLogger().fine("[ItemsAdder] Item not found: " + spec.getId());
                }
                return null;
            }

            Object itemObject = getItemStack.invoke(customStack);
            if (!(itemObject instanceof ItemStack)) {
                return null;
            }

            ItemStack item = ((ItemStack) itemObject).clone();
            item.setAmount(Math.max(1, spec.getAmount()));
            return item;

        } catch (IllegalAccessException | InvocationTargetException e) {
            if (debugMode) {
                plugin.getLogger().warning("[ItemsAdder] Failed to build item '" + spec.getId() +
                        "': " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Get display name for an ItemsAdder item.
     * Used by AnnouncementManager.
     * @param itemId the item ID
     * @return the display name, or null if not found
     */
    @Nullable
    public Component getDisplayName(String itemId) {
        if (!isPresent() || itemId == null || getDisplayName == null) {
            return null;
        }

        try {
            Object customStack = getInstance.invoke(null, itemId);
            if (customStack == null) {
                return null;
            }

            Object result = getDisplayName.invoke(customStack);
            if (result instanceof Component c) {
                return c;
            } else if (result instanceof String s && !s.isEmpty()) {
                return Component.text(s);
            }
        } catch (Exception e) {
            if (debugMode) {
                plugin.getLogger().fine("[ItemsAdder] Failed to get display name: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Get lore for an ItemsAdder item.
     * Used by AnnouncementManager.
     * @param itemId the item ID
     * @return the lore, or null if not found
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public List<Component> getLore(String itemId) {
        if (!isPresent() || itemId == null || getLore == null) {
            return null;
        }

        try {
            Object customStack = getInstance.invoke(null, itemId);
            if (customStack == null) {
                return null;
            }

            Object result = getLore.invoke(customStack);
            if (result instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Component) {
                    return (List<Component>) result;
                } else if (first instanceof String) {
                    List<Component> components = new ArrayList<>();
                    for (Object obj : list) {
                        components.add(Component.text(String.valueOf(obj)));
                    }
                    return components;
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                plugin.getLogger().fine("[ItemsAdder] Failed to get lore: " + e.getMessage());
            }
        }
        return null;
    }
}
