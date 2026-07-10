package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.model.DropSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Backend for vanilla Minecraft items.
 * Supports custom names and lore using MiniMessage format.
 */
public class VanillaBackend implements DropBackend {

    @Override
    public boolean isPresent() {
        return true;
    }

    @Override
    public String getName() {
        return "Vanilla";
    }

    @Override
    @Nullable
    public ItemStack createItem(DropSpec spec, @Nullable Player creator) {
        return buildItem(spec);
    }

    /**
     * Build a vanilla ItemStack from the specification.
     * @param spec the drop specification
     * @return the constructed ItemStack
     */
    private ItemStack buildItem(DropSpec spec) {
        // A VANILLA spec with no material is a config error (usually a typo'd
        // `type:` falling back to VANILLA). Defaulting to STONE shipped stone
        // blocks to players silently; failing the build surfaces it in the log.
        Material material = spec.material();
        if (material == null) {
            return null;
        }
        ItemStack item = new ItemStack(material, Math.max(1, spec.amount()));

        var meta = item.getItemMeta();
        if (meta != null) {
            // Apply custom name if provided
            if (spec.name() != null && !spec.name().isEmpty()) {
                meta.displayName(MiniMessage.miniMessage().deserialize(spec.name()));
            }

            // Apply custom lore if provided
            if (!spec.lore().isEmpty()) {
                List<Component> loreComponents = new ArrayList<>();
                for (String line : spec.lore()) {
                    loreComponents.add(MiniMessage.miniMessage().deserialize(line));
                }
                meta.lore(loreComponents);
            }

            item.setItemMeta(meta);
        }

        return item;
    }
}
