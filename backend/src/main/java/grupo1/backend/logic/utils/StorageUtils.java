package grupo1.backend.logic.utils;

import java.util.Locale;

public final class StorageUtils {

    private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;
    private static final double MEGABYTES_PER_PAGE = 8.0 / 1024.0;
    private static final String LOG_TYPE = "LOG";
    private static final String VIEW_TYPE = "VIEW";
    private static final int FIRST_NONCLUSTERED_INDEX_ID = 2;

    private StorageUtils() {
    }

    public static double bytesToMegabytes(long bytes) {
        return bytes / BYTES_PER_MEGABYTE;
    }

    public static Integer percent(double part, double whole) {
        if (whole <= 0) {
            return null;
        }
        return (int) Math.min(100, Math.round(100.0 * part / whole));
    }

    public static String growthLabel(int growth, boolean percentGrowth) {
        if (growth <= 0) {
            return null;
        }
        return percentGrowth ? growth + "%" : InstanceUtils.formatMegabytes(growth * MEGABYTES_PER_PAGE);
    }

    public static String fileKind(String typeDesc) {
        return LOG_TYPE.equals(typeDesc) ? "Log" : "Datos";
    }

    public static String objectKind(int indexId, String typeDesc) {
        if (indexId >= FIRST_NONCLUSTERED_INDEX_ID) {
            return "Índice";
        }
        return typeDesc != null && typeDesc.contains(VIEW_TYPE) ? "Vista indexada" : "Tabla";
    }

    public static String qualifiedName(String schemaName, String objectName) {
        return schemaName + "." + objectName;
    }

    public static String signed(double megabytes) {
        String sign = megabytes >= 0 ? "+" : "-";
        return sign + InstanceUtils.formatMegabytes(Math.abs(megabytes));
    }

    public static String trend(double percent) {
        return String.format(Locale.US, "%s %.1f%% vs. mes anterior", percent >= 0 ? "↑" : "↓", Math.abs(percent));
    }

    public static String runway(double months) {
        return String.format(Locale.US, "Proyección: %.1f meses de holgura", months);
    }
}
