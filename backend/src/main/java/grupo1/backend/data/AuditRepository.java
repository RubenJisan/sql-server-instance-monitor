package grupo1.backend.data;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import grupo1.backend.logic.utils.AuditUtils;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class AuditRepository {

    public record LoginRow(
            String name,
            String type,
            boolean disabled,
            LocalDateTime createDate,
            String defaultDatabase,
            boolean builtInAdmin,
            Boolean policyChecked,
            Boolean expirationChecked,
            Integer daysUntilExpiration,
            LocalDate expiresOn,
            Boolean locked
    ) {
    }

    public record RoleRow(String name, boolean fixed) {
    }

    public record MembershipRow(String roleName, String memberName) {
    }

    public record PermissionRow(
            String grantee,
            String granteeType,
            String permission,
            String state,
            String object,
            String grantor
    ) {
    }

    public record InvalidObjectRow(
            String schemaName,
            String objectName,
            String typeDesc,
            String referencedName
    ) {
    }

    private static final String LOGINS_QUERY = """
            SELECT p.name AS name,
                   p.type AS type,
                   p.is_disabled AS disabled,
                   p.create_date AS createDate,
                   p.default_database_name AS defaultDatabase,
                   CAST(CASE WHEN p.principal_id = 1 THEN 1 ELSE 0 END AS BIT) AS builtInAdmin,
                   l.is_policy_checked AS policyChecked,
                   l.is_expiration_checked AS expirationChecked,
                   CAST(LOGINPROPERTY(p.name, 'DaysUntilExpiration') AS INT) AS daysUntilExpiration,
                   CAST(DATEADD(DAY, CAST(LOGINPROPERTY(p.name, 'DaysUntilExpiration') AS INT), SYSDATETIME()) AS DATE) AS expiresOn,
                   CAST(LOGINPROPERTY(p.name, 'IsLocked') AS INT) AS locked
            FROM sys.server_principals AS p
            LEFT JOIN sys.sql_logins AS l ON l.principal_id = p.principal_id
            WHERE p.type IN ('S', 'U', 'G')
              AND p.name NOT LIKE '##%'
            ORDER BY p.name
            """;

    private static final String ROLES_QUERY = """
            SELECT r.name AS name,
                   r.is_fixed_role AS fixedRole
            FROM sys.server_principals AS r
            WHERE r.type = 'R'
            ORDER BY r.is_fixed_role DESC, r.name
            """;

    private static final String MEMBERSHIPS_QUERY = """
            SELECT r.name AS roleName,
                   m.name AS memberName
            FROM sys.server_role_members AS rm
            JOIN sys.server_principals AS r ON r.principal_id = rm.role_principal_id
            JOIN sys.server_principals AS m ON m.principal_id = rm.member_principal_id
            """;

    private static final String PERMISSIONS_QUERY = """
            SELECT grantee.name AS grantee,
                   grantee.type AS granteeType,
                   pe.permission_name AS permission,
                   pe.state_desc AS state,
                   CASE pe.class
                       WHEN 100 THEN 'SERVER'
                       WHEN 101 THEN CONCAT('LOGIN::', target.name COLLATE DATABASE_DEFAULT)
                       WHEN 105 THEN CONCAT('ENDPOINT::', ep.name COLLATE DATABASE_DEFAULT)
                       ELSE pe.class_desc COLLATE DATABASE_DEFAULT
                   END AS objectName,
                   grantor.name AS grantor
            FROM sys.server_permissions AS pe
            JOIN sys.server_principals AS grantee ON grantee.principal_id = pe.grantee_principal_id
            LEFT JOIN sys.server_principals AS grantor ON grantor.principal_id = pe.grantor_principal_id
            LEFT JOIN sys.server_principals AS target ON pe.class = 101 AND target.principal_id = pe.major_id
            LEFT JOIN sys.endpoints AS ep ON pe.class = 105 AND ep.endpoint_id = pe.major_id
            WHERE grantee.name NOT LIKE '##%'
              AND NOT (pe.permission_name = 'CONNECT SQL' AND pe.state_desc = 'GRANT')
              AND NOT (grantee.name = 'public' AND pe.class = 105)
            ORDER BY grantee.name, pe.permission_name
            """;

    private static final String USER_DATABASES_QUERY = """
            SELECT name
            FROM sys.databases
            WHERE database_id > 4
              AND state_desc = 'ONLINE'
            ORDER BY name
            """;

    private static final String INVALID_OBJECTS_BODY = """
            SELECT SCHEMA_NAME(o.schema_id) AS schemaName,
                   o.name AS objectName,
                   o.type_desc AS typeDesc,
                   CONCAT(COALESCE(d.referenced_schema_name + '.', ''), d.referenced_entity_name) AS referencedName
            FROM sys.sql_expression_dependencies AS d
            JOIN sys.objects AS o ON o.object_id = d.referencing_id
            WHERE d.referencing_class = 1
              AND d.referenced_class = 1
              AND d.referenced_id IS NULL
              AND d.is_ambiguous = 0
              AND d.is_caller_dependent = 0
              AND o.is_ms_shipped = 0
              AND d.referenced_database_name IS NULL
              AND d.referenced_server_name IS NULL
            ORDER BY SCHEMA_NAME(o.schema_id), o.name
            """;

    private final ActiveConnection activeConnection;

    public List<LoginRow> findLogins() {
        return activeConnection.jdbcTemplate().query(LOGINS_QUERY, (rs, rowNum) -> new LoginRow(
                rs.getString("name"),
                rs.getString("type"),
                rs.getBoolean("disabled"),
                rs.getTimestamp("createDate").toLocalDateTime(),
                rs.getString("defaultDatabase"),
                rs.getBoolean("builtInAdmin"),
                nullableBoolean(rs, "policyChecked"),
                nullableBoolean(rs, "expirationChecked"),
                nullableInt(rs, "daysUntilExpiration"),
                nullableDate(rs, "expiresOn"),
                nullableBoolean(rs, "locked")
        ));
    }

    public List<RoleRow> findRoles() {
        return activeConnection.jdbcTemplate().query(ROLES_QUERY, (rs, rowNum) -> new RoleRow(
                rs.getString("name"),
                rs.getBoolean("fixedRole")
        ));
    }

    public List<MembershipRow> findMemberships() {
        return activeConnection.jdbcTemplate().query(MEMBERSHIPS_QUERY, (rs, rowNum) -> new MembershipRow(
                rs.getString("roleName"),
                rs.getString("memberName")
        ));
    }

    public List<PermissionRow> findPermissions() {
        return activeConnection.jdbcTemplate().query(PERMISSIONS_QUERY, (rs, rowNum) -> new PermissionRow(
                rs.getString("grantee"),
                rs.getString("granteeType"),
                rs.getString("permission"),
                rs.getString("state"),
                rs.getString("objectName"),
                rs.getString("grantor")
        ));
    }

    public List<String> findUserDatabases() {
        return activeConnection.jdbcTemplate().query(USER_DATABASES_QUERY, (rs, rowNum) -> rs.getString("name"));
    }

    public List<InvalidObjectRow> findInvalidObjects(String database) {
        String sql = "EXEC %s.sys.sp_executesql N'%s'".formatted(
                AuditUtils.quoteName(database),
                INVALID_OBJECTS_BODY.replace("'", "''")
        );
        return activeConnection.jdbcTemplate().query(sql, (rs, rowNum) -> new InvalidObjectRow(
                rs.getString("schemaName"),
                rs.getString("objectName"),
                rs.getString("typeDesc"),
                rs.getString("referencedName")
        ));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate nullableDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
