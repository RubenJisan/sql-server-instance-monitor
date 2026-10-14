package grupo1.backend.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import grupo1.backend.logic.utils.AuditUtils;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class StorageRepository {

    public record FileRow(
            String name,
            String physicalName,
            String typeDesc,
            String fileGroup,
            double sizeMb,
            Double usedMb,
            int growth,
            boolean percentGrowth
    ) {
    }

    public record LogUsageRow(long totalBytes, long usedBytes) {
    }

    public record ObjectRow(
            String schemaName,
            String objectName,
            String indexName,
            int indexId,
            String typeDesc,
            double sizeMb
    ) {
    }

    public record GrowthRow(LocalDate monthStart, double sizeMb) {
    }

    private static final int OBJECT_LIMIT = 10;
    private static final int GROWTH_MONTHS = 6;

    private static final String DATABASES_QUERY = """
            SELECT name
            FROM sys.databases
            WHERE state_desc = 'ONLINE'
            ORDER BY CASE WHEN database_id <= 4 THEN 1 ELSE 0 END, name
            """;

    private static final String FILES_BODY = """
            SELECT f.name AS name,
                   f.physical_name AS physicalName,
                   f.type_desc AS typeDesc,
                   fg.name AS fileGroup,
                   CAST(f.size AS BIGINT) * 8 / 1024.0 AS sizeMb,
                   CAST(CASE WHEN f.type = 0 THEN FILEPROPERTY(f.name, 'SpaceUsed') END AS BIGINT) * 8 / 1024.0 AS usedMb,
                   f.growth AS growth,
                   f.is_percent_growth AS percentGrowth
            FROM sys.database_files AS f
            LEFT JOIN sys.filegroups AS fg ON fg.data_space_id = f.data_space_id
            WHERE f.type IN (0, 1)
            ORDER BY f.type, f.file_id
            """;

    private static final String LOG_USAGE_BODY = """
            SELECT total_log_size_in_bytes AS totalBytes,
                   used_log_space_in_bytes AS usedBytes
            FROM sys.dm_db_log_space_usage
            """;

    private static final String OBJECTS_BODY = """
            SELECT TOP (%d)
                   SCHEMA_NAME(o.schema_id) AS schemaName,
                   o.name AS objectName,
                   i.name AS indexName,
                   i.index_id AS indexId,
                   o.type_desc AS typeDesc,
                   SUM(ps.reserved_page_count) * 8 / 1024.0 AS sizeMb
            FROM sys.dm_db_partition_stats AS ps
            JOIN sys.objects AS o ON o.object_id = ps.object_id
            JOIN sys.indexes AS i ON i.object_id = ps.object_id AND i.index_id = ps.index_id
            WHERE o.is_ms_shipped = 0
              AND o.type IN ('U', 'V')
            GROUP BY o.schema_id, o.name, o.type_desc, i.index_id, i.name
            ORDER BY SUM(ps.reserved_page_count) DESC
            """;

    private static final String GROWTH_QUERY = """
            SELECT DATEFROMPARTS(YEAR(b.backup_start_date), MONTH(b.backup_start_date), 1) AS monthStart,
                   MAX(b.backup_size) / 1048576.0 AS sizeMb
            FROM msdb.dbo.backupset AS b
            WHERE b.database_name = ?
              AND b.type = 'D'
              AND b.backup_start_date >= DATEADD(MONTH, -%d, DATEFROMPARTS(YEAR(SYSDATETIME()), MONTH(SYSDATETIME()), 1))
            GROUP BY DATEFROMPARTS(YEAR(b.backup_start_date), MONTH(b.backup_start_date), 1)
            ORDER BY DATEFROMPARTS(YEAR(b.backup_start_date), MONTH(b.backup_start_date), 1)
            """;

    private final ActiveConnection activeConnection;

    public List<String> findDatabases() {
        return activeConnection.jdbcTemplate().query(DATABASES_QUERY, (rs, rowNum) -> rs.getString("name"));
    }

    public List<FileRow> findFiles(String database) {
        return activeConnection.jdbcTemplate().query(inDatabase(database, FILES_BODY), (rs, rowNum) -> new FileRow(
                rs.getString("name"),
                rs.getString("physicalName"),
                rs.getString("typeDesc"),
                rs.getString("fileGroup"),
                rs.getDouble("sizeMb"),
                nullableDouble(rs, "usedMb"),
                rs.getInt("growth"),
                rs.getBoolean("percentGrowth")
        ));
    }

    public List<LogUsageRow> findLogUsage(String database) {
        return activeConnection.jdbcTemplate().query(inDatabase(database, LOG_USAGE_BODY), (rs, rowNum) -> new LogUsageRow(
                rs.getLong("totalBytes"),
                rs.getLong("usedBytes")
        ));
    }

    public List<ObjectRow> findObjects(String database) {
        return activeConnection.jdbcTemplate().query(inDatabase(database, OBJECTS_BODY.formatted(OBJECT_LIMIT)), (rs, rowNum) -> new ObjectRow(
                rs.getString("schemaName"),
                rs.getString("objectName"),
                rs.getString("indexName"),
                rs.getInt("indexId"),
                rs.getString("typeDesc"),
                rs.getDouble("sizeMb")
        ));
    }

    public List<GrowthRow> findGrowth(String database) {
        return activeConnection.jdbcTemplate().query(GROWTH_QUERY.formatted(GROWTH_MONTHS - 1), (rs, rowNum) -> new GrowthRow(
                rs.getDate("monthStart").toLocalDate(),
                rs.getDouble("sizeMb")
        ), database);
    }

    private static String inDatabase(String database, String body) {
        return "EXEC %s.sys.sp_executesql N'%s'".formatted(
                AuditUtils.quoteName(database),
                body.replace("'", "''")
        );
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
