package dev.oakheart.oakmobdrops.drop;

import dev.oakheart.oakmobdrops.DropStatistics;
import dev.oakheart.oakmobdrops.announcement.AnnouncementManager;
import dev.oakheart.oakmobdrops.backend.DropBackend;
import dev.oakheart.oakmobdrops.backend.DropRouter;
import dev.oakheart.oakmobdrops.model.DropEntry;
import dev.oakheart.oakmobdrops.model.DropSpec;
import dev.oakheart.oakmobdrops.util.CommandExecutor;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.enchantments.Enchantment;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Processes individual drop entries for killed mobs.
 * Handles chance calculations, looting enchantment, and item distribution.
 */
public class DropProcessor {
    private final JavaPlugin plugin;
    private final DropRouter router;
    private final AnnouncementManager announcementManager;
    private final CommandExecutor commandExecutor;
    private final DropStatistics statistics;
    private final boolean debugMode;
    private final boolean useLootingEnchant;
    private final double lootingMultiplier;
    private final boolean enableStatistics;

    public DropProcessor(JavaPlugin plugin, DropRouter router,
                         AnnouncementManager announcementManager,
                         DropStatistics statistics,
                         boolean debugMode, boolean useLootingEnchant,
                         double lootingMultiplier, boolean enableStatistics) {
        this.plugin = plugin;
        this.router = router;
        this.announcementManager = announcementManager;
        this.commandExecutor = new CommandExecutor(plugin, debugMode);
        this.statistics = statistics;
        this.debugMode = debugMode;
        this.useLootingEnchant = useLootingEnchant;
        this.lootingMultiplier = lootingMultiplier;
        this.enableStatistics = enableStatistics;
    }

    /**
     * Process a single drop entry for a killed mob.
     *
     * @param drop the drop entry to process
     * @param entity the killed entity
     * @param recipient the player to receive the drop
     * @param killer the player who killed the mob (may be null)
     * @return true if the drop succeeded
     */
    public boolean processDrop(DropEntry drop, LivingEntity entity, Player recipient, Player killer) {
        // Calculate final chance with looting modifier
        double finalChance = calculateFinalChance(drop, killer);

        // Record attempt for statistics
        if (statistics != null && enableStatistics) {
            statistics.recordAttempt(entity.getType(), drop.getId(), finalChance);
        }

        // Roll for drop
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll > finalChance) {
            if (debugMode) {
                plugin.getLogger().info("Drop '" + drop.getId() + "' failed: " +
                        roll + " > " + finalChance);
            }
            return false;
        }

        if (debugMode) {
            plugin.getLogger().info("Drop '" + drop.getId() + "' succeeded! " +
                    roll + " <= " + finalChance);
        }

        // Calculate amount
        int amount = calculateAmount(drop);

        // Build the item
        DropSpec specWithAmount = drop.getSpec().withAmount(amount);
        DropBackend backend = router.forType(specWithAmount.getType());

        ItemStack built = backend.createItem(specWithAmount, drop.isDropAtLocation() ? null : recipient);
        if (built == null) {
            plugin.getLogger().warning("Drop build failed for '" + drop.getId() +
                    "' using backend " + specWithAmount.getType());
            return false;
        }

        // Distribute the item
        distributeItem(drop, entity, recipient, built);

        // Record success for statistics
        if (statistics != null && enableStatistics) {
            statistics.recordSuccess(entity.getType(), drop.getId(), amount, finalChance, recipient);
        }

        // Send announcement
        announcementManager.sendAnnouncement(drop, entity.getType(), finalChance,
                recipient, amount, built);

        // Execute commands if configured
        if (!drop.getCommands().isEmpty()) {
            Component itemName = getItemDisplayName(built, drop);
            commandExecutor.executeCommands(drop.getCommands(), recipient, entity.getType(),
                    itemName, amount, finalChance);
        }

        return true;
    }

    /**
     * Get the display name of an item for command placeholders.
     */
    private Component getItemDisplayName(ItemStack item, DropEntry drop) {
        var meta = item.getItemMeta();
        if (meta != null && meta.displayName() != null) {
            return meta.displayName();
        }

        // Fallback to material name
        try {
            return Component.translatable(item.getType());
        } catch (Throwable ignored) {
            return Component.text(item.getType().name());
        }
    }

    /**
     * Calculate the final drop chance including looting enchantment.
     */
    private double calculateFinalChance(DropEntry drop, Player killer) {
        double finalChance = drop.getChance();

        if (useLootingEnchant && killer != null) {
            ItemStack weapon = killer.getInventory().getItemInMainHand();
            if (weapon.hasItemMeta() && weapon.containsEnchantment(Enchantment.LOOTING)) {
                int lootingLevel = weapon.getEnchantmentLevel(Enchantment.LOOTING);
                finalChance = finalChance * (1 + (lootingLevel * lootingMultiplier));

                if (debugMode) {
                    plugin.getLogger().info("Drop '" + drop.getId() + "': Looting " + lootingLevel +
                            " applied. Chance: " + (drop.getChance() * 100) + "% -> " +
                            (finalChance * 100) + "%");
                }
            }
        }

        return Math.max(0.0, Math.min(1.0, finalChance));
    }

    /**
     * Calculate the amount to drop based on the configured range.
     */
    private int calculateAmount(DropEntry drop) {
        if (drop.getMinAmount() == drop.getMaxAmount()) {
            return drop.getMinAmount();
        }
        return ThreadLocalRandom.current().nextInt(drop.getMinAmount(), drop.getMaxAmount() + 1);
    }

    /**
     * Distribute the item either by dropping it or giving it to the player.
     */
    private void distributeItem(DropEntry drop, LivingEntity entity, Player recipient, ItemStack item) {
        if (drop.isDropAtLocation()) {
            entity.getWorld().dropItemNaturally(entity.getLocation(), item);
        } else {
            Map<Integer, ItemStack> leftovers = recipient.getInventory().addItem(item);
            if (!leftovers.isEmpty()) {
                leftovers.values().forEach(stack ->
                    recipient.getWorld().dropItemNaturally(recipient.getLocation(), stack));
            }
        }
    }
}
