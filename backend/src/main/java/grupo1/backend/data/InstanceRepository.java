package grupo1.backend.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class InstanceRepository {

    public record InstanceRow(
            String machineName,
            String instanceName,
            String productVersion,
            String productLevel,
            String edition,
            LocalDateTime startTime,
            long uptimeSeconds,
            String loginName,
            String hostDistribution,
            int cpuCount,
            long physicalMemoryMb
    ) {
    }

    public record MemoryRow(
            Long maxServerMemoryMb,
            Long targetServerMemoryMb,
            Long totalServerMemoryMb,
            Long databaseCacheMb,
            Long processMemoryMb
    ) {
    }

    public record DatabaseRow(
            String name,
            String stateDesc,
            boolean readOnly,
            String recoveryModel,
            Double sizeMb,
            boolean system
    ) {
    }

    public record SessionRow(int userSessions, int maxConnections) {
    }

    private static final String INSTANCE_QUERY = """
            SELECT CAST(SERVERPROPERTY('MachineName') AS NVARCHAR(128)) AS machineName,
                   CAST(SERVERPROPERTY('InstanceName') AS NVARCHAR(128)) AS instanceName,
                   CAST(SERVERPROPERTY('ProductVersion') AS NVARCHAR(50)) AS productVersion,
                   CAST(SERVERPROPERTY('ProductLevel') AS NVARCHAR(50)) AS productLevel,
                   CAST(SERVERPROPERTY('Edition') AS NVARCHAR(128)) AS edition,
                   info.sqlserver_start_time AS startTime,
                   DATEDIFF(SECOND, info.sqlserver_start_time, SYSDATETIME()) AS uptimeSeconds,
                   SYSTEM_USER AS loginName,
                   hostInfo.host_distribution AS hostDistribution,
                   info.cpu_count AS cpuCount,
                   info.physical_memory_kb / 1024 AS physicalMemoryMb
            FROM sys.dm_os_sys_info AS info
            CROSS JOIN sys.dm_os_host_info AS hostInfo
            """;

    private static final String MEMORY_QUERY = """
            SELECT (SELECT CAST(value_in_use AS BIGINT)
                    FROM sys.configurations
                    WHERE name = 'max server memory (MB)') AS maxServerMemoryMb,
                   (SELECT cntr_value / 1024
                    FROM sys.dm_os_performance_counters
                    WHERE object_name LIKE '%Memory Manager%'
                      AND counter_name = 'Target Server Memory (KB)') AS targetServerMemoryMb,
                   (SELECT cntr_value / 1024
                    FROM sys.dm_os_performance_counters
                    WHERE object_name LIKE '%Memory Manager%'
                      AND counter_name = 'Total Server Memory (KB)') AS totalServerMemoryMb,
                   (SELECT cntr_value / 1024
                    FROM sys.dm_os_performance_counters
                    WHERE object_name LIKE '%Memory Manager%'
                      AND counter_name = 'Database Cache Memory (KB)') AS databaseCacheMb,
                   (SELECT physical_memory_in_use_kb / 1024
                    FROM sys.dm_os_process_memory) AS processMemoryMb
            """;

    private static final String DATABASES_QUERY = """
            SELECT d.name AS name,
                   d.state_desc AS stateDesc,
                   d.is_read_only AS readOnly,
                   d.recovery_model_desc AS recoveryModel,
                   CAST(SUM(CAST(f.size AS BIGINT)) * 8 / 1024.0 AS DECIMAL(18, 2)) AS sizeMb,
                   CAST(CASE WHEN d.database_id <= 4 THEN 1 ELSE 0 END AS BIT) AS isSystem
            FROM sys.databases AS d
            LEFT JOIN sys.master_files AS f ON f.database_id = d.database_id
            GROUP BY d.database_id, d.name, d.state_desc, d.is_read_only, d.recovery_model_desc
            ORDER BY CASE WHEN d.database_id <= 4 THEN 1 ELSE 0 END, d.name
            """;

    private static final String SESSIONS_QUERY = """
            SELECT COUNT(*) AS userSessions,
                   @@MAX_CONNECTIONS AS maxConnections
            FROM sys.dm_exec_sessions
            WHERE is_user_process = 1
            """;

    private final ActiveConnection activeConnection;

    public InstanceRow findInstance() {
        return activeConnection.jdbcTemplate().queryForObject(INSTANCE_QUERY, (rs, rowNum) -> new InstanceRow(
                rs.getString("machineName"),
                rs.getString("instanceName"),
                rs.getString("productVersion"),
                rs.getString("productLevel"),
                rs.getString("edition"),
                rs.getTimestamp("startTime").toLocalDateTime(),
                rs.getLong("uptimeSeconds"),
                rs.getString("loginName"),
                rs.getString("hostDistribution"),
                rs.getInt("cpuCount"),
                rs.getLong("physicalMemoryMb")
        ));
    }

    public MemoryRow findMemory() {
        return activeConnection.jdbcTemplate().queryForObject(MEMORY_QUERY, (rs, rowNum) -> new MemoryRow(
                nullableLong(rs, "maxServerMemoryMb"),
                nullableLong(rs, "targetServerMemoryMb"),
                nullableLong(rs, "totalServerMemoryMb"),
                nullableLong(rs, "databaseCacheMb"),
                nullableLong(rs, "processMemoryMb")
        ));
    }

    public List<DatabaseRow> findDatabases() {
        return activeConnection.jdbcTemplate().query(DATABASES_QUERY, (rs, rowNum) -> new DatabaseRow(
                rs.getString("name"),
                rs.getString("stateDesc"),
                rs.getBoolean("readOnly"),
                rs.getString("recoveryModel"),
                nullableDouble(rs, "sizeMb"),
                rs.getBoolean("isSystem")
        ));
    }

    public SessionRow findSessions() {
        return activeConnection.jdbcTemplate().queryForObject(SESSIONS_QUERY, (rs, rowNum) -> new SessionRow(
                rs.getInt("userSessions"),
                rs.getInt("maxConnections")
        ));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
