package dev.oakheart.oakmobdrops.model;

import java.util.List;

/**
 * Represents a single drop entry with its chance, amount range, and specifications.
 */
public record DropEntry(String id, double chance, boolean dropAtLocation, String announcement,
                        boolean playSound, int minAmount, int maxAmount, DropSpec spec,
                        List<String> commands) {

    public DropEntry {
        commands = commands != null ? List.copyOf(commands) : List.of();
    }

    public DropEntry(String id, double chance, boolean dropAtLocation, String announcement,
                     boolean playSound, int minAmount, int maxAmount, DropSpec spec) {
        this(id, chance, dropAtLocation, announcement, playSound, minAmount, maxAmount, spec, List.of());
    }
}
