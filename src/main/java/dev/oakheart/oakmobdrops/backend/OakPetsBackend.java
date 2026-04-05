package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.OakMobDrops;
import dev.oakheart.oakmobdrops.model.DropSpec;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Drop backend for OakPets pet vouchers.
 * Uses reflection to call OakPets' PetVoucherBuilder API.
 * The item-id in the drop spec is the OakPets pet definition ID.
 */
public class OakPetsBackend implements DropBackend {

    private final OakMobDrops plugin;
    private volatile Constructor<?> builderConstructor;
    private volatile Method buildMethod;
    private volatile boolean reflectionInitialized;
    private volatile boolean reflectionFailed;

    public OakPetsBackend(OakMobDrops plugin) {
        this.plugin = plugin;
        initializeReflection();
    }

    private void initializeReflection() {
        try {
            Class<?> builderClass = Class.forName("dev.oakheart.oakpets.api.PetVoucherBuilder");
            builderConstructor = builderClass.getConstructor(String.class);
            buildMethod = builderClass.getMethod("build");
            reflectionInitialized = true;
        } catch (Exception e) {
            reflectionFailed = true;
            if (plugin.isDebugMode()) {
                plugin.getLogger().warning("[OakPets Backend] Reflection init failed: " + e.getMessage());
            }
        }
    }

    @Override
    public boolean isPresent() {
        return plugin.getServer().getPluginManager().isPluginEnabled("OakPets")
                && reflectionInitialized && !reflectionFailed;
    }

    @Override
    public String getName() {
        return "OakPets";
    }

    @Override
    @Nullable
    public ItemStack createItem(DropSpec spec, @Nullable Player creator) {
        if (!isPresent() || spec.id() == null) return null;

        try {
            Object builder = builderConstructor.newInstance(spec.id());
            Object result = buildMethod.invoke(builder);
            if (result instanceof ItemStack item) {
                item.setAmount(Math.max(1, spec.amount()));
                return item;
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.getLogger().warning("[OakPets] Failed to create voucher for '" + spec.id()
                        + "': " + e.getMessage());
            }
        }

        return null;
    }

    @Override
    @Nullable
    public Component getDisplayName(String itemId) {
        if (!isPresent() || itemId == null) return null;

        try {
            Object builder = builderConstructor.newInstance(itemId);
            Object result = buildMethod.invoke(builder);
            if (result instanceof ItemStack item) {
                var meta = item.getItemMeta();
                if (meta != null) return meta.displayName();
            }
        } catch (Exception ignored) {}

        return null;
    }

    @Override
    @Nullable
    public List<Component> getLore(String itemId) {
        if (!isPresent() || itemId == null) return null;

        try {
            Object builder = builderConstructor.newInstance(itemId);
            Object result = buildMethod.invoke(builder);
            if (result instanceof ItemStack item) {
                var meta = item.getItemMeta();
                if (meta != null) return meta.lore();
            }
        } catch (Exception ignored) {}

        return null;
    }
}
