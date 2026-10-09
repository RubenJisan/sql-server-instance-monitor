package grupo1.backend.logic.dto;

import java.util.List;

public record AuditDTO(
        Metrics metrics,
        List<User> users,
        List<Role> roles,
        List<Privilege> privileges,
        List<InvalidObject> invalidObjects,
        Scan scan
) {

    public record Metrics(
            int logins,
            int enabledLogins,
            int disabledLogins,
            int roles,
            int sysadminRoles,
            int expiringAccounts,
            int expiringDaysAhead,
            int invalidObjects,
            int invalidSchemas
    ) {
    }

    public record User(
            String name,
            String type,
            String principalRole,
            List<String> roles,
            String status,
            String statusLabel,
            String expiresAt,
            String createdAt,
            String defaultDatabase
    ) {
    }

    public record Role(
            String name,
            boolean fixed,
            String privileges,
            int userCount,
            List<String> members,
            boolean critical
    ) {
    }

    public record Privilege(
            String grantee,
            String privilege,
            String object,
            String grantor,
            String status,
            String statusLabel,
            String source
    ) {
    }

    public record InvalidObject(
            String database,
            String schema,
            String name,
            String type,
            String problem
    ) {
    }

    public record Scan(List<String> databases, List<String> warnings) {
    }
}