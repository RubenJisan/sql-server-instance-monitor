package grupo1.backend.logic.utils;

import java.util.Locale;

public final class InstanceUtils {

    private static final double MEGABYTES_PER_GIGABYTE = 1024.0;
    private static final double MEGABYTES_PER_TERABYTE = 1024.0 * 1024.0;

    private InstanceUtils() {
    }

    public static String marketingYear(String productVersion) {
        if (productVersion == null || productVersion.isBlank()) {
            return "";
        }
        return switch (productVersion.split("\\.")[0]) {
            case "17" -> "2025";
            case "16" -> "2022";
            case "15" -> "2019";
            case "14" -> "2017";
            case "13" -> "2016";
            case "12" -> "2014";
            case "11" -> "2012";
            case "10" -> "2008";
            default -> "";
        };
    }

    public static String formatUptime(long totalSeconds) {
        long minutes = totalSeconds / 60 % 60;
        long hours = totalSeconds / 3600 % 24;
        long days = totalSeconds / 86400;

        if (days > 0) {
            return "%d d %d h %d min".formatted(days, hours, minutes);
        }
        if (hours > 0) {
            return "%d h %d min".formatted(hours, minutes);
        }
        return "%d min".formatted(minutes);
    }

    public static String formatMegabytes(Double megabytes) {
        if (megabytes == null) {
            return null;
        }
        if (megabytes >= MEGABYTES_PER_TERABYTE) {
            return String.format(Locale.US, "%.1f TB", megabytes / MEGABYTES_PER_TERABYTE);
        }
        if (megabytes >= MEGABYTES_PER_GIGABYTE) {
            return String.format(Locale.US, "%.1f GB", megabytes / MEGABYTES_PER_GIGABYTE);
        }
        return String.format(Locale.US, "%.1f MB", megabytes);
    }

    public static Integer percent(Long part, Long whole) {
        if (part == null || whole == null || whole <= 0) {
            return null;
        }
        return (int) Math.min(100, Math.round(100.0 * part / whole));
    }
}
