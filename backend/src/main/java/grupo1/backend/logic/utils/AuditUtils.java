package grupo1.backend.logic.utils;

import java.util.List;

public final class AuditUtils {

    private static final String DEFAULT_ROLE = "public";
    private static final List<String> ROLE_PRIORITY = List.of(
            "sysadmin", "securityadmin", "serveradmin", "setupadmin",
            "processadmin", "diskadmin", "dbcreator", "bulkadmin"
    );

    private AuditUtils() {
    }

    public static String principalRole(List<String> roles) {
        return ROLE_PRIORITY.stream()
                .filter(roles::contains)
                .findFirst()
                .or(() -> roles.stream().findFirst())
                .orElse(DEFAULT_ROLE);
    }

    public static String loginType(String type) {
        return switch (type) {
            case "S" -> "Login SQL";
            case "U" -> "Login Windows";
            case "G" -> "Grupo Windows";
            default -> type;
        };
    }

    public static String objectType(String typeDesc) {
        return typeDesc == null ? null : typeDesc.replace('_', ' ');
    }

    public static String quoteName(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }
}