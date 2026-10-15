package grupo1.backend.logic.utils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PerformanceUtils {

    public static final String MONITOR_PROGRAM = "Microsoft JDBC Driver for SQL Server";

    public record TimeRange(String code, String label, int minutes, int bucketMinutes) {
    }

    private static final List<TimeRange> RANGES = List.of(
            new TimeRange("1h", "Última hora", 60, 5),
            new TimeRange("6h", "Últimas 6 horas", 360, 30),
            new TimeRange("24h", "Últimas 24 horas", 1440, 120)
    );

    private static final Map<String, String> LOCK_MODES = Map.ofEntries(
            Map.entry("S", "bloqueo compartido"),
            Map.entry("U", "bloqueo de actualización"),
            Map.entry("X", "bloqueo exclusivo"),
            Map.entry("IS", "intención compartida"),
            Map.entry("IU", "intención de actualización"),
            Map.entry("IX", "intención exclusiva"),
            Map.entry("SIU", "compartido con intención de actualización"),
            Map.entry("SIX", "compartido con intención exclusiva"),
            Map.entry("UIX", "actualización con intención exclusiva"),
            Map.entry("SCH_S", "estabilidad de esquema"),
            Map.entry("SCH_M", "modificación de esquema"),
            Map.entry("BU", "carga masiva")
    );

    private static final String LOCK_WAIT_PREFIX = "LCK_M_";
    private static final int PROGRAM_MAX_LENGTH = 30;

    private PerformanceUtils() {
    }

    public static TimeRange parseRange(String code) {
        String requested = code == null || code.isBlank() ? RANGES.getFirst().code() : code.trim();
        return RANGES.stream()
                .filter(range -> range.code().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Rango no válido: %s. Use 1h, 6h o 24h.".formatted(requested)));
    }

    public static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public static double percent(double part, double whole) {
        return whole <= 0 ? 0 : round(Math.min(100.0, 100.0 * part / whole));
    }

    public static String formatPercent(double value) {
        return String.format(Locale.US, "%.1f%%", value);
    }

    public static String formatMilliseconds(double milliseconds) {
        if (milliseconds < 1) {
            return String.format(Locale.US, "%.2f ms", milliseconds);
        }
        if (milliseconds < 1000) {
            return "%d ms".formatted(Math.round(milliseconds));
        }
        if (milliseconds < 60_000) {
            return String.format(Locale.US, "%.1f s", milliseconds / 1000.0);
        }
        return String.format(Locale.US, "%.1f min", milliseconds / 60_000.0);
    }

    public static String formatTotal(double milliseconds) {
        if (milliseconds < 60_000) {
            return formatMilliseconds(milliseconds);
        }
        long seconds = Math.round(milliseconds / 1000.0);
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        return hours > 0
                ? "%dh %02dm".formatted(hours, minutes)
                : "%dm %02ds".formatted(minutes, seconds % 60);
    }

    public static String formatClock(long totalSeconds) {
        long seconds = Math.max(0, totalSeconds);
        return "%02d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    public static String formatWait(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return seconds >= 3600
                ? "%d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60)
                : "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }

    public static String formatAge(long totalSeconds) {
        long seconds = Math.max(0, totalSeconds);
        if (seconds < 60) {
            return "hace %d s".formatted(seconds);
        }
        if (seconds < 3600) {
            return "hace %d min".formatted(seconds / 60);
        }
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        return minutes == 0 ? "hace %d h".formatted(hours) : "hace %d h %d min".formatted(hours, minutes);
    }

    public static String formatCpuTime(long milliseconds) {
        return milliseconds < 1000
                ? "%d ms".formatted(milliseconds)
                : String.format(Locale.US, "%.1f s", milliseconds / 1000.0);
    }

    public static String formatCount(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    public static String trend(long difference, String period) {
        if (difference == 0) {
            return "Sin cambio vs. " + period;
        }
        return "%s %d%% vs. %s".formatted(difference > 0 ? "↑" : "↓", Math.abs(difference), period);
    }

    public static String programLabel(String programName) {
        if (programName == null || programName.isBlank()) {
            return "Sin programa";
        }
        String name = programName.trim();
        if (name.startsWith("SQLAgent")) {
            return "SQLAgent";
        }
        if (name.startsWith("Microsoft SQL Server Management Studio")) {
            return "SSMS";
        }
        return name.length() > PROGRAM_MAX_LENGTH ? name.substring(0, PROGRAM_MAX_LENGTH - 1) + "…" : name;
    }

    public static String collapse(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() > maxLength ? collapsed.substring(0, maxLength - 1) + "…" : collapsed;
    }

    public static String waitDescription(String waitType) {
        if (waitType == null || waitType.isBlank()) {
            return null;
        }
        if (!waitType.startsWith(LOCK_WAIT_PREFIX)) {
            return waitType;
        }
        String mode = waitType.substring(LOCK_WAIT_PREFIX.length()).replaceAll("_LOW_PRIORITY|_ABORT_BLOCKERS", "");
        String description = LOCK_MODES.get(mode);
        return description == null ? waitType : "%s (%s)".formatted(waitType, description);
    }

    public static String safeFileName(String name) {
        return name == null ? "instancia" : name.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}