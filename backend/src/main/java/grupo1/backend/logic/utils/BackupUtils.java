package grupo1.backend.logic.utils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BackupUtils {

    private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;
    private static final Pattern FAILED_COMMAND = Pattern.compile(
            "BACKUP\\s+(DATABASE|LOG)\\s+\\[?([^\\s\\[\\].,]+)\\]?", Pattern.CASE_INSENSITIVE);

    public record ParsedFailure(String database, String type) {
    }

    private BackupUtils() {
    }

    public static String normalizeDatabase(String database) {
        return database == null || database.isBlank() ? null : database.trim();
    }

    /** Código de msdb.dbo.backupset.type → nombre usado por el frontend. */
    public static String typeName(String code) {
        return switch (code == null ? "" : code) {
            case "D" -> "FULL";
            case "I" -> "DIFF";
            case "L" -> "LOG";
            default -> code;
        };
    }

    /** Extrae base de datos y tipo del mensaje "BACKUP failed to complete the command ...". */
    public static Optional<ParsedFailure> parseFailure(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = FAILED_COMMAND.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String type = "LOG".equalsIgnoreCase(matcher.group(1))
                ? "LOG"
                : text.toUpperCase().contains("DIFFERENTIAL") ? "DIFF" : "FULL";
        return Optional.of(new ParsedFailure(matcher.group(2), type));
    }

    public static Long toMinutes(long seconds) {
        return Math.max(1L, (seconds + 59) / 60);
    }

    public static Double toMegabytes(Long bytes) {
        if (bytes == null) {
            return null;
        }
        return bytes / BYTES_PER_MEGABYTE;
    }

    public static String formatAge(long totalHours) {
        long days = totalHours / 24;
        long hours = totalHours % 24;
        return days == 0 ? "hace %d h".formatted(hours) : "hace %d d %d h".formatted(days, hours);
    }

    public static String formatFrequency(int count, LocalDateTime first, LocalDateTime last) {
        if (count < 2 || first == null || last == null) {
            return "Sin datos suficientes";
        }
        long averageMinutes = Duration.between(first, last).toMinutes() / (count - 1);
        if (averageMinutes < 90) {
            return "Cada ~%d min".formatted(Math.max(1, averageMinutes));
        }
        if (averageMinutes < 48 * 60) {
            return "Cada ~%d h".formatted(Math.round(averageMinutes / 60.0));
        }
        return "Cada ~%d días".formatted(Math.round(averageMinutes / 1440.0));
    }

    public static String formatRetention(LocalDateTime oldest, LocalDateTime now) {
        if (oldest == null) {
            return null;
        }
        long days = Duration.between(oldest, now).toDays();
        return days < 1 ? "Menos de 1 día en historial" : "%d días en historial".formatted(days);
    }

    public static String directoryOf(String device) {
        if (device == null) {
            return null;
        }
        int separator = Math.max(device.lastIndexOf('\\'), device.lastIndexOf('/'));
        return separator > 0 ? device.substring(0, separator) : null;
    }

    public static String joinPath(String directory, String fileName) {
        String separator = directory.contains("\\") ? "\\" : "/";
        return directory.endsWith(separator) ? directory + fileName : directory + separator + fileName;
    }

    public static String safeFileName(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
