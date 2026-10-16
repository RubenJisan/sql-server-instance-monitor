package grupo1.backend.data;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class BackupRepository {

    private static final String BACKUP_TYPES = "('D', 'I', 'L')";   // FULL, DIFF, LOG

    private static final String BACKUP_COLUMNS = """
            bs.backup_set_id, bs.database_name, bs.type, bs.backup_start_date,
            DATEDIFF(SECOND, bs.backup_start_date, bs.backup_finish_date) AS duration_seconds,
            bs.backup_size, bs.is_damaged, bs.has_backup_checksums
            """;

    public record BackupRow(long id, String database, String type, LocalDateTime startedAt,
                            long durationSeconds, Long sizeBytes, boolean damaged, boolean checksum) {
    }

    public record DailyRow(LocalDate day, int total, int damaged) {
    }

    public record TypeStatsRow(String type, int recentCount, LocalDateTime recentFirst,
                               LocalDateTime recentLast, LocalDateTime oldest) {
    }

    public record MediaRow(String device, boolean checksum) {
    }

    public record FailureRow(LocalDateTime at, String text) {
    }

    private record Scope(String clause, Object[] args) {
    }

    private static Scope scope(String database) {
        if (!hasText(database)) {
            return new Scope("1 = 1", new Object[0]);
        }
        return new Scope("bs.database_name = ?", new Object[]{database});
    }

    public List<String> findRecoveryModels(String database) {
        boolean filtered = hasText(database);
        String sql = "SELECT DISTINCT d.recovery_model_desc FROM sys.databases d "
                + "WHERE d.database_id <> 2 AND d.source_database_id IS NULL"
                + (filtered ? " AND d.name = ?" : "");
        return jdbc().query(sql, (rs, i) -> rs.getString(1), filtered ? new Object[]{database} : new Object[0]);
    }

    public List<String> findBackupableDatabases(String database) {
        boolean filtered = hasText(database);
        String sql = "SELECT d.name FROM sys.databases d "
                + "WHERE d.database_id <> 2 AND d.state_desc = 'ONLINE' AND d.source_database_id IS NULL"
                + (filtered ? " AND d.name = ?" : "")
                + " ORDER BY d.name";
        return jdbc().query(sql, (rs, i) -> rs.getString(1), filtered ? new Object[]{database} : new Object[0]);
    }

    /** Bases en línea que nunca han tenido un respaldo completo exitoso. */
    public List<String> findDatabasesWithoutFull(String database) {
        boolean filtered = hasText(database);
        String sql = """
            SELECT d.name
            FROM sys.databases d
            WHERE d.database_id <> 2 AND d.source_database_id IS NULL AND d.state_desc = 'ONLINE'
              AND NOT EXISTS (
                  SELECT 1 FROM msdb.dbo.backupset bs
                  WHERE bs.database_name COLLATE DATABASE_DEFAULT = d.name COLLATE DATABASE_DEFAULT
                    AND bs.type = 'D' AND bs.is_damaged = 0)
            """ + (filtered ? " AND d.name = ?" : "") + " ORDER BY d.name";
        return jdbc().query(sql, (rs, i) -> rs.getString(1), filtered ? new Object[]{database} : new Object[0]);
    }

    /** Si no hay permiso sobre xp_readerrorlog lanza DataAccessException; el service lo informa como warning. */
    public List<FailureRow> findBackupFailures() {
        return jdbc().query("EXEC master.dbo.xp_readerrorlog 0, 1, N'BACKUP failed to complete'",
                (rs, i) -> new FailureRow(toLocal(rs.getTimestamp("LogDate")), rs.getString("Text")));
    }


    private static final RowMapper<BackupRow> BACKUP_MAPPER = (rs, i) -> new BackupRow(
            rs.getLong("backup_set_id"),
            rs.getString("database_name"),
            rs.getString("type"),
            toLocal(rs.getTimestamp("backup_start_date")),
            rs.getLong("duration_seconds"),
            toLong(rs.getBigDecimal("backup_size")),
            rs.getBoolean("is_damaged"),
            rs.getBoolean("has_backup_checksums")
    );

    private final ActiveConnection activeConnection;

    // AJUSTAR: usa exactamente la misma forma que InstanceRepository para obtener el JdbcTemplate
    private JdbcTemplate jdbc() {
        return activeConnection.jdbcTemplate();
    }

    /* ------------------------- Consultas ------------------------- */

    public LocalDateTime findServerNow() {
        return jdbc().queryForObject("SELECT SYSDATETIME()", (rs, i) -> toLocal(rs.getTimestamp(1)));
    }

    public List<BackupRow> findRecentBackups(String database, int limit) {
        Scope scope = scope(database);
        String sql = "SELECT TOP (?) " + BACKUP_COLUMNS + """
                FROM msdb.dbo.backupset bs
                WHERE bs.type IN %s AND %s
                ORDER BY bs.backup_start_date DESC, bs.backup_set_id DESC
                """.formatted(BACKUP_TYPES, scope.clause());
        return jdbc().query(sql, BACKUP_MAPPER, args(scope, limit));
    }

    /** Último respaldo exitoso (no dañado). fullOnly = true limita a respaldos completos. */
    public Optional<BackupRow> findLastSuccess(String database, boolean fullOnly) {
        Scope scope = scope(database);
        String types = fullOnly ? "('D')" : BACKUP_TYPES;
        String sql = "SELECT TOP (1) " + BACKUP_COLUMNS + """
                FROM msdb.dbo.backupset bs
                WHERE bs.is_damaged = 0 AND bs.type IN %s AND %s
                ORDER BY bs.backup_start_date DESC, bs.backup_set_id DESC
                """.formatted(types, scope.clause());
        return jdbc().query(sql, BACKUP_MAPPER, args(scope)).stream().findFirst();
    }

    /** Respaldos por día en la ventana indicada (incluye el día actual del servidor). */
    public List<DailyRow> findDailySummary(String database, int days) {
        Scope scope = scope(database);
        String sql = """
                SELECT CAST(bs.backup_start_date AS date) AS backup_day,
                       COUNT(*) AS total,
                       SUM(CASE WHEN bs.is_damaged = 1 THEN 1 ELSE 0 END) AS damaged
                FROM msdb.dbo.backupset bs
                WHERE bs.type IN %s
                  AND bs.backup_start_date >= DATEADD(DAY, ?, CAST(SYSDATETIME() AS date))
                  AND %s
                GROUP BY CAST(bs.backup_start_date AS date)
                """.formatted(BACKUP_TYPES, scope.clause());
        return jdbc().query(sql,
                (rs, i) -> new DailyRow(rs.getDate("backup_day").toLocalDate(), rs.getInt("total"), rs.getInt("damaged")),
                args(scope, -(days - 1)));
    }

    /** Frecuencia y antigüedad observadas por tipo de respaldo (últimos 30 días / historial completo). */
    public List<TypeStatsRow> findTypeStats(String database) {
        Scope scope = scope(database);
        String recent = "bs.backup_start_date >= DATEADD(DAY, -30, SYSDATETIME())";
        String sql = """
                SELECT bs.type,
                       SUM(CASE WHEN %1$s THEN 1 ELSE 0 END) AS recent_count,
                       MIN(CASE WHEN %1$s THEN bs.backup_start_date END) AS recent_first,
                       MAX(CASE WHEN %1$s THEN bs.backup_start_date END) AS recent_last,
                       MIN(bs.backup_start_date) AS oldest
                FROM msdb.dbo.backupset bs
                WHERE bs.is_damaged = 0 AND bs.type IN %2$s AND %3$s
                GROUP BY bs.type
                """.formatted(recent, BACKUP_TYPES, scope.clause());
        return jdbc().query(sql,
                (rs, i) -> new TypeStatsRow(
                        rs.getString("type"),
                        rs.getInt("recent_count"),
                        toLocal(rs.getTimestamp("recent_first")),
                        toLocal(rs.getTimestamp("recent_last")),
                        toLocal(rs.getTimestamp("oldest"))),
                args(scope));
    }

    /** Dispositivo (archivo) del respaldo más reciente: sirve para deducir destino y verificación. */
    public Optional<MediaRow> findLatestMedia(String database) {
        Scope scope = scope(database);
        String sql = """
                SELECT TOP (1) mf.physical_device_name, bs.has_backup_checksums
                FROM msdb.dbo.backupset bs
                JOIN msdb.dbo.backupmediafamily mf ON mf.media_set_id = bs.media_set_id
                WHERE bs.is_damaged = 0 AND bs.type IN %s AND %s
                ORDER BY bs.backup_start_date DESC, bs.backup_set_id DESC
                """.formatted(BACKUP_TYPES, scope.clause());
        return jdbc().query(sql,
                (rs, i) -> new MediaRow(rs.getString("physical_device_name"), rs.getBoolean("has_backup_checksums")),
                args(scope)).stream().findFirst();
    }

    public String findDefaultBackupDirectory() {
        return jdbc().queryForObject(
                "SELECT CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512))", String.class);
    }

    public void backupFull(String databaseName, String filePath) {
        // databaseName viene de sys.databases y filePath lo arma el service con nombres saneados
        String sql = "BACKUP DATABASE " + quote(databaseName)
                + " TO DISK = N'" + filePath.replace("'", "''") + "' WITH CHECKSUM, INIT";
        jdbc().execute(sql);
    }

    private static Object[] args(Scope scope, Object... leading) {
        Object[] all = Arrays.copyOf(leading, leading.length + scope.args().length);
        System.arraycopy(scope.args(), 0, all, leading.length, scope.args().length);
        return all;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String quote(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Long toLong(BigDecimal value) {
        return value == null ? null : value.longValue();
    }
}
