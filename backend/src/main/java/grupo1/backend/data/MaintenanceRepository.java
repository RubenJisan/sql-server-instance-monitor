package grupo1.backend.data;

import java.sql.PreparedStatement;
import java.util.List;
import grupo1.backend.logic.dto.MaintenanceDTO.Table;
import grupo1.backend.logic.utils.AuditUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MaintenanceRepository {
    private final int timeoutSeconds;

    public MaintenanceRepository(@Value("${app.maintenance.timeoutSeconds:300}") int timeoutSeconds) {
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public record ModuleRow(int objectId, String schema, String name, String type,
                            boolean schemaBound, boolean encrypted, boolean signed) {
        public boolean repairable() {
            return !schemaBound && !encrypted && !signed;
        }
    }

    public void requireVisibility(JdbcTemplate jdbc, String database) {
        Integer visible = jdbc.queryForObject(inDatabase(database, """
                SELECT HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DEFINITION')
                """), Integer.class);
        if (!Integer.valueOf(1).equals(visible)) {
            throw new IllegalArgumentException("Se requiere VIEW DEFINITION en la base seleccionada para un análisis completo.");
        }
    }

    public List<Table> tables(JdbcTemplate jdbc, String database) {
        return jdbc.query(inDatabase(database, """
                SELECT t.object_id, SCHEMA_NAME(t.schema_id) AS schemaName, t.name,
                       COUNT(s.stats_id) AS [statistics],
                       CONVERT(varchar(33), MIN(p.last_updated), 126) AS lastUpdated,
                       MAX(p.modification_counter) AS modifications
                FROM sys.tables t
                LEFT JOIN sys.stats s ON s.object_id = t.object_id
                OUTER APPLY sys.dm_db_stats_properties(s.object_id, s.stats_id) p
                WHERE t.is_ms_shipped = 0 AND t.is_memory_optimized = 0
                GROUP BY t.object_id, t.schema_id, t.name
                ORDER BY SCHEMA_NAME(t.schema_id), t.name
                """), (rs, rowNum) -> new Table(rs.getInt("object_id"), rs.getString("schemaName"),
                rs.getString("name"), rs.getInt("statistics"), rs.getString("lastUpdated"),
                rs.getObject("modifications", Long.class)));
    }

    public List<ModuleRow> modules(JdbcTemplate jdbc, String database) {
        return jdbc.query(inDatabase(database, """
                SELECT o.object_id, SCHEMA_NAME(o.schema_id) AS schemaName, o.name, o.type_desc,
                       m.is_schema_bound, CASE WHEN m.definition IS NULL THEN 1 ELSE 0 END AS encrypted,
                       CASE WHEN EXISTS (SELECT 1 FROM sys.crypt_properties c
                            WHERE c.class = 1 AND c.major_id = o.object_id) THEN 1 ELSE 0 END AS [signed]
                FROM sys.objects o JOIN sys.sql_modules m ON m.object_id = o.object_id
                WHERE o.is_ms_shipped = 0 AND o.type IN ('V', 'P', 'FN', 'IF', 'TF', 'TR')
                ORDER BY SCHEMA_NAME(o.schema_id), o.name
                """), (rs, rowNum) -> new ModuleRow(rs.getInt("object_id"), rs.getString("schemaName"),
                rs.getString("name"), rs.getString("type_desc"), rs.getBoolean("is_schema_bound"),
                rs.getBoolean("encrypted"), rs.getBoolean("signed")));
    }

    public List<String> unresolved(JdbcTemplate jdbc, String database, String schema, String name) {
        return jdbc.query(inDatabase(database, """
                SELECT DISTINCT CONCAT(COALESCE(referenced_schema_name + '.', ''), referenced_entity_name)
                FROM sys.dm_sql_referenced_entities(@module, 'OBJECT')
                WHERE referenced_id IS NULL AND referenced_entity_name IS NOT NULL
                  AND referenced_database_name IS NULL AND referenced_server_name IS NULL
                  AND is_caller_dependent = 0 AND is_ambiguous = 0
                """, "@module nvarchar(517)", "@module=?"),
                (rs, rowNum) -> rs.getString(1), qualified(schema, name));
    }

    public void updateStatistics(JdbcTemplate jdbc, String database, Table table, boolean fullScan) {
        execute(jdbc, inDatabase(database, "UPDATE STATISTICS " + qualified(table.schema(), table.name())
                + (fullScan ? " WITH FULLSCAN" : "") + ";"));
    }

    public void refresh(JdbcTemplate jdbc, String database, ModuleRow module) {
        execute(jdbc, inDatabase(database, "EXEC sys.sp_refreshsqlmodule @name=@module;",
                "@module nvarchar(517)", "@module=?"), qualified(module.schema(), module.name()));
    }

    private void execute(JdbcTemplate jdbc, String sql, String... parameters) {
        jdbc.execute(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(timeoutSeconds);
            for (int index = 0; index < parameters.length; index++) {
                statement.setNString(index + 1, parameters[index]);
            }
            return statement;
        }, (org.springframework.jdbc.core.PreparedStatementCallback<Boolean>) PreparedStatement::execute);
    }

    static String qualified(String schema, String name) {
        return AuditUtils.quoteName(schema) + "." + AuditUtils.quoteName(name);
    }

    static String inDatabase(String database, String sql) {
        return inDatabase(database, sql, null, null);
    }

    private static String inDatabase(String database, String sql, String declarations, String arguments) {
        return "EXEC " + AuditUtils.quoteName(database) + ".sys.sp_executesql N'"
                + sql.replace("'", "''") + "'"
                + (declarations == null ? "" : ", N'" + declarations + "', " + arguments);
    }
}
