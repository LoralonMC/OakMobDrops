package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.model.DropSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Override
    public boolean give(Player player, DropSpec spec) {
        ItemStack item = buildItem(spec);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            leftovers.values().forEach(stack ->
                player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
        return true;
    }

    @Override
    public boolean drop(Location location, DropSpec spec) {
        location.getWorld().dropItemNaturally(location, buildItem(spec));
        return true;
    }

    /**
     * Build a vanilla ItemStack from the specification.
     * @param spec the drop specification
     * @return the constructed ItemStack
     */
    private ItemStack buildItem(DropSpec spec) {
        Material material = (spec.material() != null) ? spec.material() : Material.STONE;
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
