package dev.oakheart.oakmobdrops.model;

import org.bukkit.Material;
import java.util.List;

/**
 * Specification for an item drop, including type, ID, and custom properties.
 */
public class DropSpec {
    private DropType type;
    private String id;
    private int amount;
    private Material material;
    private String name;
    private List<String> lore;

    public DropSpec() {
        this.amount = 1;
    }

    public DropSpec(DropType type, String id, int amount, Material material,
                    String name, List<String> lore) {
        this.type = type;
        this.id = id;
        this.amount = amount;
        this.material = material;
        this.name = name;
        this.lore = lore;
    }

    /**
     * Creates a copy of this spec with a different amount.
     * @param newAmount the new amount to use
     * @return a new DropSpec instance with the specified amount
     */
    public DropSpec withAmount(int newAmount) {
        return new DropSpec(this.type, this.id, newAmount, this.material, this.name, this.lore);
    }

    // Getters and setters
    public DropType getType() {
        return type;
    }

    public void setType(DropType type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public Material getMaterial() {
        return material;
    }

    public void setMaterial(Material material) {
        this.material = material;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore;
    }

    @Override
    public String toString() {
        return "DropSpec{" + type + ", id=" + id + ", amount=" + amount + ", material=" + material + "}";
    }
}
