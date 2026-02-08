package dev.oakheart.oakmobdrops.model;

import org.bukkit.Material;
import java.util.List;

/**
 * Specification for an item drop, including type, ID, and custom properties.
 */
public record DropSpec(DropType type, String id, int amount, Material material,
                       String name, List<String> lore) {

    public DropSpec {
        if (amount < 1) amount = 1;
        if (lore == null) lore = List.of();
    }

    /**
     * Creates a copy of this spec with a different amount.
     * @param newAmount the new amount to use
     * @return a new DropSpec instance with the specified amount
     */
    public DropSpec withAmount(int newAmount) {
        return new DropSpec(this.type, this.id, newAmount, this.material, this.name, this.lore);
    }

    @Override
    public String toString() {
        return "DropSpec{" + type + ", id=" + id + ", amount=" + amount + ", material=" + material + "}";
    }
}
