package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.model.DropSpec;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backend for Nexo plugin.
 * Supports both old and new API versions using reflection with method caching.
 */
public class NexoBackend implements DropBackend {
    private final JavaPlugin plugin;
    private final boolean debugMode;
    private volatile boolean itemsReady = false;

    // Cached reflection methods
    private static volatile Method itemFromId;
    private static volatile Method optionalItemFromId;
    private static volatile Method isPresent;
    private static volatile Method get;
    private static volatile Method getItemName;
    private static volatile Method getDisplayName;
    private static volatile Method getLore;
    private static volatile Method setAmount;
    private static volatile Method getFinalItemStack;
    private static volatile Method build;
    private static volatile boolean reflectionInitialized = false;
    private static volatile boolean reflectionFailed = false;
    private static volatile boolean usingNewAPI = false;

    public NexoBackend(JavaPlugin plugin) {
        this.plugin = plugin;
        this.debugMode = plugin.getConfig().getBoolean("settings.debug", false);
        initializeReflection();
        registerItemsLoadedListener();
    }

    /**
     * Initialize reflection methods once.
     * Attempts to use new API first, falls back to old API.
     */
    private void initializeReflection() {
        if (reflectionInitialized || reflectionFailed) {
            return;
        }

        synchronized (NexoBackend.class) {
            if (reflectionInitialized || reflectionFailed) {
                return;
            }

            try {
                Class<?> nexoItemsClass = Class.forName("com.nexomc.nexo.api.NexoItems");

                // Try new API first
                try {
                    itemFromId = nexoItemsClass.getMethod("itemFromId", String.class);
                    usingNewAPI = true;
                    if (debugMode) {
                        plugin.getLogger().info("[Nexo Backend] Using new API (itemFromId)");
                    }
                } catch (NoSuchMethodException e) {
                    // Fall back to old API
                    optionalItemFromId = nexoItemsClass.getMethod("optionalItemFromId", String.class);
                    isPresent = java.util.Optional.class.getMethod("isPresent");
                    get = java.util.Optional.class.getMethod("get");
                    usingNewAPI = false;
                    if (debugMode) {
                        plugin.getLogger().info("[Nexo Backend] Using old API (optionalItemFromId)");
                    }
                }

                reflectionInitialized = true;

            } catch (Exception e) {
                reflectionFailed = true;
                if (debugMode) {
                    plugin.getLogger().warning("[Nexo Backend] Reflection initialization failed: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Register listener for Nexo items loaded event.
     */
    private void registerItemsLoadedListener() {
        if (!isPresent()) {
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Class<? extends org.bukkit.event.Event> eventClass =
                    (Class<? extends org.bukkit.event.Event>)
                            Class.forName("com.nexomc.nexo.api.events.NexoItemsLoadedEvent");

            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    new org.bukkit.event.Listener() {},
                    EventPriority.NORMAL,
                    (listener, event) -> {
                        itemsReady = true;
                        if (debugMode) {
                            plugin.getLogger().info("[Nexo Backend] Items loaded and ready");
                        }
                    },
                    plugin
            );
        } catch (ClassNotFoundException e) {
            // Event doesn't exist in this version, assume items are always ready
            itemsReady = true;
        }
    }

    @Override
    public boolean isPresent() {
        return plugin.getServer().getPluginManager().isPluginEnabled("Nexo")
                && reflectionInitialized;
    }

    @Override
    public String getName() {
        return "Nexo";
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
     * Build an ItemStack using Nexo API.
     * @param spec the drop specification
     * @return the created ItemStack, or null if failed
     */
    @Nullable
    private ItemStack buildItemStack(DropSpec spec) {
        if (!isPresent() || spec.getId() == null) {
            return null;
        }

        try {
            Object builder = getBuilder(spec.getId());
            if (builder == null) {
                if (!itemsReady && debugMode) {
                    plugin.getLogger().fine("[Nexo] Items not ready yet: " + spec.getId());
                }
                return null;
            }

            // Try to set amount on the builder
            if (setAmount == null) {
                try {
                    setAmount = builder.getClass().getMethod("setAmount", Integer.class);
                } catch (NoSuchMethodException ignored) {}
            }

            if (setAmount != null) {
                try {
                    setAmount.invoke(builder, Math.max(1, spec.getAmount()));
                } catch (Exception ignored) {}
            }

            // Get the final item stack
            ItemStack stack = getFinalStack(builder);
            if (stack != null) {
                stack.setAmount(Math.max(1, spec.getAmount()));
                return stack;
            }

        } catch (Exception e) {
            if (debugMode) {
                plugin.getLogger().warning("[Nexo] Failed to build item '" + spec.getId() +
                        "': " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }

        return null;
    }

    /**
     * Get builder object for the given item ID.
     * Supports both old and new API.
     */
    @Nullable
    private Object getBuilder(String itemId) throws Exception {
        if (usingNewAPI) {
            return itemFromId.invoke(null, itemId);
        } else {
            Object optional = optionalItemFromId.invoke(null, itemId);
            if (optional == null) {
                return null;
            }

            boolean present = (boolean) isPresent.invoke(optional);
            if (!present) {
                return null;
            }

            return get.invoke(optional);
        }
    }

    /**
     * Get final ItemStack from builder.
     * Tries getFinalItemStack() first, then build().
     */
    @Nullable
    private ItemStack getFinalStack(Object builder) throws Exception {
        // Try getFinalItemStack() first
        if (getFinalItemStack == null) {
            try {
                getFinalItemStack = builder.getClass().getMethod("getFinalItemStack");
            } catch (NoSuchMethodException ignored) {}
        }

        if (getFinalItemStack != null) {
            try {
                Object result = getFinalItemStack.invoke(builder);
                if (result instanceof ItemStack is) {
                    return is;
                }
            } catch (Exception ignored) {}
        }

        // Fall back to build()
        if (build == null) {
            try {
                build = builder.getClass().getMethod("build");
            } catch (NoSuchMethodException ignored) {}
        }

        if (build != null) {
            Object result = build.invoke(builder);
            if (result instanceof ItemStack is) {
                return is;
            }
        }

        return null;
    }

    /**
     * Get display name for a Nexo item.
     * Used by AnnouncementManager.
     * @param itemId the item ID
     * @return the display name, or null if not found
     */
    @Nullable
    public Component getDisplayName(String itemId) {
        if (!isPresent() || itemId == null) {
            return null;
        }

        try {
            Object builder = getBuilder(itemId);
            if (builder == null) {
                return null;
            }

            // Try getItemName() first (modern API)
            if (getItemName == null) {
                try {
                    getItemName = builder.getClass().getMethod("getItemName");
                } catch (NoSuchMethodException ignored) {}
            }

            if (getItemName != null) {
                try {
                    Object result = getItemName.invoke(builder);
                    if (result instanceof Component c) return c;
                    if (result instanceof String s && !s.isEmpty()) return Component.text(s);
                } catch (Exception ignored) {}
            }

            // Fall back to getDisplayName()
            if (getDisplayName == null) {
                try {
                    getDisplayName = builder.getClass().getMethod("getDisplayName");
                } catch (NoSuchMethodException ignored) {}
            }

            if (getDisplayName != null) {
                Object result = getDisplayName.invoke(builder);
                if (result instanceof Component c) return c;
                if (result instanceof String s && !s.isEmpty()) return Component.text(s);
            }

            // Last resort: get from built item
            ItemStack stack = getFinalStack(builder);
            if (stack != null) {
                var meta = stack.getItemMeta();
                if (meta != null && meta.displayName() != null) {
                    return meta.displayName();
                }
            }

        } catch (Exception e) {
            if (debugMode) {
                plugin.getLogger().fine("[Nexo] Failed to get display name: " + e.getMessage());
            }
        }

        return null;
    }

    /**
     * Get lore for a Nexo item.
     * Used by AnnouncementManager.
     * @param itemId the item ID
     * @return the lore, or null if not found
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public List<Component> getLore(String itemId) {
        if (!isPresent() || itemId == null) {
            return null;
        }

        try {
            Object builder = getBuilder(itemId);
            if (builder == null) {
                return null;
            }

            // Try getLore() or lore() methods
            if (getLore == null) {
                try {
                    getLore = builder.getClass().getMethod("getLore");
                } catch (NoSuchMethodException e) {
                    try {
                        getLore = builder.getClass().getMethod("lore");
                    } catch (NoSuchMethodException ignored) {}
                }
            }

            if (getLore != null) {
                Object result = getLore.invoke(builder);
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
            }

            // Fall back to built item lore
            ItemStack stack = getFinalStack(builder);
            if (stack != null) {
                var meta = stack.getItemMeta();
                if (meta != null && meta.lore() != null && !meta.lore().isEmpty()) {
                    return meta.lore();
                }
            }

        } catch (Exception e) {
            if (debugMode) {
                plugin.getLogger().fine("[Nexo] Failed to get lore: " + e.getMessage());
            }
        }

        return null;
    }
}
