package dev.oakheart.oakmobdrops.util;

import org.bukkit.entity.EntityType;

import java.util.Locale;

/**
 * Shared formatting utilities for display strings.
 * All methods are thread-safe (no shared mutable state).
 */
public final class FormatUtils {

    private FormatUtils() {}

    /**
     * Format an enum name to title case.
     * Example: SKELETON_HORSE -> Skeleton Horse
     */
    public static String formatEnumName(String enumName) {
        String[] words = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /**
     * Format an entity type name to title case.
     * Example: EntityType.SKELETON_HORSE -> "Skeleton Horse"
     */
    public static String formatEntityName(EntityType type) {
        return formatEnumName(type.name());
    }

    /**
     * Format a probability (0.0-1.0) as a percentage string without a % sign.
     * Thread-safe (uses String.format instead of DecimalFormat).
     * Example: 0.0001 -> "0.01", 0.5 -> "50"
     */
    public static String formatChancePercent(double probability0to1) {
        return stripTrailingZeros(String.format(Locale.ROOT, "%.4f", probability0to1 * 100.0));
    }

    /**
     * Format a probability as a fraction string.
     * Example: 0.0001 -> "1/10,000"
     */
    public static String formatChanceFraction(double p) {
        if (!(p > 0.0)) return "Never";
        if (p >= 1.0) return "Always";

        long denominator = (long) Math.ceil(1.0 / p);
        return "1/" + String.format(Locale.ROOT, "%,d", denominator);
    }

    /**
     * Format a percentage value with trailing zeros removed and a % suffix.
     * Example: 10.0 -> "10%", 10.5 -> "10.5%", 0.0005 -> "0.0005%"
     */
    public static String formatStatPercent(double value) {
        return stripTrailingZeros(String.format(Locale.ROOT, "%.4f", value)) + "%";
    }

    private static String stripTrailingZeros(String formatted) {
        formatted = formatted.replaceAll("(\\.\\d*?)0+$", "$1");
        formatted = formatted.replaceAll("\\.$", "");
        return formatted;
    }
}
