package dev.oakheart.oakmobdrops.model;

import java.util.Collections;
import java.util.List;

/**
 * Represents a single drop entry with its chance, amount range, and specifications.
 */
public class DropEntry {
    private String id;
    private double chance;
    private boolean dropAtLocation;
    private String announcement;
    private boolean playSound;
    private int minAmount;
    private int maxAmount;
    private DropSpec spec;
    private List<String> commands;

    public DropEntry(String id, double chance, boolean dropAtLocation, String announcement,
                     boolean playSound, int minAmount, int maxAmount, DropSpec spec) {
        this(id, chance, dropAtLocation, announcement, playSound, minAmount, maxAmount, spec, Collections.emptyList());
    }

    public DropEntry(String id, double chance, boolean dropAtLocation, String announcement,
                     boolean playSound, int minAmount, int maxAmount, DropSpec spec, List<String> commands) {
        this.id = id;
        this.chance = chance;
        this.dropAtLocation = dropAtLocation;
        this.announcement = announcement;
        this.playSound = playSound;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.spec = spec;
        this.commands = commands != null ? commands : Collections.emptyList();
    }

    public String getId() {
        return id;
    }

    public double getChance() {
        return chance;
    }

    public boolean isDropAtLocation() {
        return dropAtLocation;
    }

    public String getAnnouncement() {
        return announcement;
    }

    public boolean isPlaySound() {
        return playSound;
    }

    public int getMinAmount() {
        return minAmount;
    }

    public int getMaxAmount() {
        return maxAmount;
    }

    public DropSpec getSpec() {
        return spec;
    }

    public List<String> getCommands() {
        return commands;
    }
}
