package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.OakMobDrops;
import dev.oakheart.oakmobdrops.model.DropSpec;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class NexoBackend implements DropBackend {
    private final OakMobDrops plugin;
    private volatile boolean itemsReady = false;

    // Cached reflection methods (instance-level to avoid stale state across reloads)
    private volatile Method apiItemFromId;
    private volatile Method apiOptionalItemFromId;
    private volatile Method optionalIsPresent;
    private volatile Method optionalGet;
    private volatile Method apiGetItemName;
    private volatile Method apiGetDisplayName;
    private volatile Method apiGetLore;
    private volatile Method apiSetAmount;
    private volatile Method apiGetFinalItemStack;
    private volatile Method apiBuild;
    private volatile boolean reflectionInitialized = false;
    private volatile boolean reflectionFailed = false;
    private volatile boolean usingNewAPI = false;

    public NexoBackend(OakMobDrops plugin) {
        this.plugin = plugin;
        initializeReflection();
        registerItemsLoadedListener();
    }

    /**
     * Initialize reflection methods.
     * Attempts to use new API first, falls back to old API.
     */
    private void initializeReflection() {
        try {
            Class<?> nexoItemsClass = Class.forName("com.nexomc.nexo.api.NexoItems");

            // Try new API first
            try {
                apiItemFromId = nexoItemsClass.getMethod("itemFromId", String.class);
                usingNewAPI = true;
                plugin.getLogger().info("[Nexo] Detected API version: 2.0+ (new API)");
            } catch (NoSuchMethodException e) {
                // Fall back to old API
                apiOptionalItemFromId = nexoItemsClass.getMethod("optionalItemFromId", String.class);
                optionalIsPresent = java.util.Optional.class.getMethod("isPresent");
                optionalGet = java.util.Optional.class.getMethod("get");
                usingNewAPI = false;
                plugin.getLogger().info("[Nexo] Detected API version: Legacy (pre-2.0)");
            }

            reflectionInitialized = true;

        } catch (Exception e) {
            reflectionFailed = true;
            if (plugin.isDebugMode()) {
                plugin.getLogger().warning("[Nexo Backend] Reflection initialization failed: " + e.getMessage());
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
                        if (plugin.isDebugMode()) {
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

    /**
     * Build an ItemStack using Nexo API.
     * @param spec the drop specification
     * @return the created ItemStack, or null if failed
     */
    @Nullable
    private ItemStack buildItemStack(DropSpec spec) {
        if (!isPresent() || spec.id() == null) {
            return null;
        }

        try {
            Object builder = getBuilder(spec.id());
            if (builder == null) {
                if (!itemsReady && plugin.isDebugMode()) {
                    plugin.getLogger().fine("[Nexo] Items not ready yet: " + spec.id());
                }
                return null;
            }

            // Try to set amount on the builder (try primitive int first, then boxed Integer)
            if (apiSetAmount == null) {
                try {
                    apiSetAmount = builder.getClass().getMethod("setAmount", int.class);
                } catch (NoSuchMethodException e) {
                    try {
                        apiSetAmount = builder.getClass().getMethod("setAmount", Integer.class);
                    } catch (NoSuchMethodException ignored) {}
                }
            }

            if (apiSetAmount != null) {
                try {
                    apiSetAmount.invoke(builder, Math.max(1, spec.amount()));
                } catch (Exception ignored) {}
            }

            // Get the final item stack
            ItemStack stack = getFinalStack(builder);
            if (stack != null) {
                stack.setAmount(Math.max(1, spec.amount()));
                return stack;
            }

        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.getLogger().warning("[Nexo] Failed to build item '" + spec.id() +
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
            return apiItemFromId.invoke(null, itemId);
        } else {
            Object optional = apiOptionalItemFromId.invoke(null, itemId);
            if (optional == null) {
                return null;
            }

            boolean present = (boolean) optionalIsPresent.invoke(optional);
            if (!present) {
                return null;
            }

            return optionalGet.invoke(optional);
        }
    }

    /**
     * Get final ItemStack from builder.
     * Tries getFinalItemStack() first, then build().
     */
    @Nullable
    private ItemStack getFinalStack(Object builder) throws Exception {
        // Try getFinalItemStack() first
        if (apiGetFinalItemStack == null) {
            try {
                apiGetFinalItemStack = builder.getClass().getMethod("getFinalItemStack");
            } catch (NoSuchMethodException ignored) {}
        }

        if (apiGetFinalItemStack != null) {
            try {
                Object result = apiGetFinalItemStack.invoke(builder);
                if (result instanceof ItemStack is) {
                    return is;
                }
            } catch (Exception ignored) {}
        }

        // Fall back to build()
        if (apiBuild == null) {
            try {
                apiBuild = builder.getClass().getMethod("build");
            } catch (NoSuchMethodException ignored) {}
        }

        if (apiBuild != null) {
            Object result = apiBuild.invoke(builder);
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
    @Override
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
            if (apiGetItemName == null) {
                try {
                    apiGetItemName = builder.getClass().getMethod("getItemName");
                } catch (NoSuchMethodException ignored) {}
            }

            if (apiGetItemName != null) {
                try {
                    Object result = apiGetItemName.invoke(builder);
                    if (result instanceof Component c) return c;
                    if (result instanceof String s && !s.isEmpty()) return Component.text(s);
                } catch (Exception ignored) {}
            }

            // Fall back to getDisplayName()
            if (apiGetDisplayName == null) {
                try {
                    apiGetDisplayName = builder.getClass().getMethod("getDisplayName");
                } catch (NoSuchMethodException ignored) {}
            }

            if (apiGetDisplayName != null) {
                Object result = apiGetDisplayName.invoke(builder);
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
            if (plugin.isDebugMode()) {
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
    @Override
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
            if (apiGetLore == null) {
                try {
                    apiGetLore = builder.getClass().getMethod("getLore");
                } catch (NoSuchMethodException e) {
                    try {
                        apiGetLore = builder.getClass().getMethod("lore");
                    } catch (NoSuchMethodException ignored) {}
                }
            }

            if (apiGetLore != null) {
                Object result = apiGetLore.invoke(builder);
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
            if (plugin.isDebugMode()) {
                plugin.getLogger().fine("[Nexo] Failed to get lore: " + e.getMessage());
            }
        }

        return null;
    }
}
