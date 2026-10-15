package grupo1.backend.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import grupo1.backend.logic.utils.AuditUtils;
import grupo1.backend.logic.utils.PerformanceUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PerformanceRepository {

    public record CpuRow(LocalDateTime sampledAt, int sqlCpu, int systemIdle) {
    }

    public record MemoryRow(LocalDateTime sampledAt, long totalKb, long availableKb) {
    }

    public record SessionCountRow(int connected, int active) {
    }

    public record ProcessRow(
            int sessionId,
            String programName,
            String loginName,
            String hostName,
            String status,
            long cpuMs,
            long memoryKb,
            long totalCpuMs
    ) {
    }

    public record QueryRow(
            String hash,
            String statement,
            long executions,
            long totalElapsedMicros,
            long totalWorkerMicros,
            long allWorkerMicros
    ) {
    }

    public record AverageRow(long executions, long totalElapsedMicros, int queryCount) {
    }

    public record RequestRow(
            int sessionId,
            String programName,
            String loginName,
            String hostName,
            String databaseName,
            String command,
            String status,
            long elapsedSeconds
    ) {
    }

    public record WaitRow(
            int sessionId,
            int blockingSessionId,
            String loginName,
            String waitType,
            long waitMs,
            String waitResource
    ) {
    }

    public record SessionDetailRow(
            int sessionId,
            String loginName,
            String programName,
            String hostName,
            String status,
            Long transactionSeconds,
            String lastStatement
    ) {
    }

    public record LockRow(String resourceType, int databaseId, String databaseName, long entityId, String requestMode) {
    }

    public record LockObjectRow(String schemaName, String objectName, String indexName) {
    }

    public record KillTargetRow(int sessionId, String programName, int blockedCount) {
    }

    private static final String SERVER_NOW_QUERY = "SELECT SYSDATETIME() AS serverNow";

    private static final String CPU_QUERY = """
            SELECT TOP (%d)
                   DATEADD(SECOND, -1 * CAST((info.ms_ticks - buffer.[timestamp]) / 1000 AS INT), SYSDATETIME()) AS sampledAt,
                   buffer.record.value('(./Record/SchedulerMonitorEvent/SystemHealth/ProcessUtilization)[1]', 'int') AS sqlCpu,
                   buffer.record.value('(./Record/SchedulerMonitorEvent/SystemHealth/SystemIdle)[1]', 'int') AS systemIdle
            FROM (
                SELECT [timestamp], CONVERT(XML, record) AS record
                FROM sys.dm_os_ring_buffers
                WHERE ring_buffer_type = N'RING_BUFFER_SCHEDULER_MONITOR'
                  AND record LIKE N'%%<SystemHealth>%%'
            ) AS buffer
            CROSS JOIN sys.dm_os_sys_info AS info
            ORDER BY buffer.[timestamp] DESC
            """;

    private static final String MEMORY_QUERY = """
            SELECT SYSDATETIME() AS sampledAt,
                   total_physical_memory_kb AS totalKb,
                   available_physical_memory_kb AS availableKb
            FROM sys.dm_os_sys_memory
            """;

    private static final String MEMORY_HISTORY_QUERY = """
            SELECT TOP (%d)
                   DATEADD(SECOND, -1 * CAST((info.ms_ticks - buffer.[timestamp]) / 1000 AS INT), SYSDATETIME()) AS sampledAt,
                   buffer.record.value('(./Record/MemoryRecord/TotalPhysicalMemory)[1]', 'bigint') AS totalKb,
                   buffer.record.value('(./Record/MemoryRecord/AvailablePhysicalMemory)[1]', 'bigint') AS availableKb
            FROM (
                SELECT [timestamp], CONVERT(XML, record) AS record
                FROM sys.dm_os_ring_buffers
                WHERE ring_buffer_type = N'RING_BUFFER_RESOURCE_MONITOR'
                  AND record LIKE N'%%<MemoryRecord>%%'
            ) AS buffer
            CROSS JOIN sys.dm_os_sys_info AS info
            ORDER BY buffer.[timestamp] DESC
            """;

    private static final String SESSION_COUNT_QUERY = """
            SELECT COUNT(*) AS connected,
                   COUNT(activeRequest.session_id) AS active
            FROM sys.dm_exec_sessions AS s
            OUTER APPLY (
                SELECT TOP (1) r.session_id
                FROM sys.dm_exec_requests AS r
                WHERE r.session_id = s.session_id
                  AND r.session_id <> @@SPID
                  AND ISNULL(s.program_name, N'') <> ?
            ) AS activeRequest
            WHERE s.is_user_process = 1
            """;

    private static final String PROCESSES_QUERY = """
            SELECT TOP (%d)
                   s.session_id AS sessionId,
                   s.program_name AS programName,
                   s.login_name AS loginName,
                   s.host_name AS hostName,
                   s.status AS status,
                   CAST(s.cpu_time AS BIGINT) AS cpuMs,
                   CAST(s.memory_usage AS BIGINT) * 8 AS memoryKb,
                   SUM(CAST(s.cpu_time AS BIGINT)) OVER () AS totalCpuMs
            FROM sys.dm_exec_sessions AS s
            WHERE s.is_user_process = 1
              AND ISNULL(s.program_name, N'') <> ?
              AND (s.status = 'running' OR s.last_request_start_time >= DATEADD(MINUTE, -?, SYSDATETIME()))
            ORDER BY s.cpu_time DESC
            """;

    private static final String QUERIES_QUERY = """
            WITH statements AS (
                SELECT qs.query_hash,
                       qs.execution_count,
                       qs.total_elapsed_time,
                       qs.total_worker_time,
                       SUBSTRING(st.text, qs.statement_start_offset / 2 + 1,
                                 (CASE qs.statement_end_offset
                                      WHEN -1 THEN DATALENGTH(st.text)
                                      ELSE qs.statement_end_offset
                                  END - qs.statement_start_offset) / 2 + 1) AS statementText,
                       ROW_NUMBER() OVER (PARTITION BY qs.query_hash ORDER BY qs.total_elapsed_time DESC) AS rankInHash
                FROM sys.dm_exec_query_stats AS qs
                CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) AS st
                WHERE qs.last_execution_time >= DATEADD(MINUTE, -?, SYSDATETIME())
                  AND ISNULL(st.text, N'') NOT LIKE N'%%sys.dm[_]%%'
            ),
            grouped AS (
                SELECT query_hash,
                       SUM(execution_count) AS executions,
                       SUM(total_elapsed_time) AS totalElapsed,
                       SUM(total_worker_time) AS totalWorker,
                       MAX(CASE WHEN rankInHash = 1 THEN statementText END) AS statementText
                FROM statements
                GROUP BY query_hash
            )
            SELECT TOP (%d)
                   LOWER(CONVERT(VARCHAR(18), query_hash, 2)) AS queryHash,
                   statementText,
                   executions,
                   totalElapsed,
                   totalWorker,
                   SUM(totalWorker) OVER () AS allWorker
            FROM grouped
            ORDER BY totalElapsed DESC
            """;

    private static final String AVERAGE_QUERY = """
            SELECT ISNULL(SUM(qs.execution_count), 0) AS executions,
                   ISNULL(SUM(qs.total_elapsed_time), 0) AS totalElapsed,
                   COUNT(*) AS queryCount
            FROM sys.dm_exec_query_stats AS qs
            CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) AS st
            WHERE qs.last_execution_time >= DATEADD(MINUTE, -?, SYSDATETIME())
              AND ISNULL(st.text, N'') NOT LIKE N'%sys.dm[_]%'
            """;

    private static final String REQUESTS_QUERY = """
            SELECT r.session_id AS sessionId,
                   s.program_name AS programName,
                   s.login_name AS loginName,
                   s.host_name AS hostName,
                   DB_NAME(r.database_id) AS databaseName,
                   r.command AS command,
                   r.status AS status,
                   DATEDIFF(SECOND, r.start_time, SYSDATETIME()) AS elapsedSeconds
            FROM sys.dm_exec_requests AS r
            JOIN sys.dm_exec_sessions AS s ON s.session_id = r.session_id
            WHERE s.is_user_process = 1
              AND r.session_id <> @@SPID
              AND ISNULL(s.program_name, N'') <> ?
            ORDER BY r.start_time
            """;

    private static final String WAITS_QUERY = """
            SELECT r.session_id AS sessionId,
                   r.blocking_session_id AS blockingSessionId,
                   s.login_name AS loginName,
                   r.wait_type AS waitType,
                   CAST(r.wait_time AS BIGINT) AS waitMs,
                   r.wait_resource AS waitResource
            FROM sys.dm_exec_requests AS r
            JOIN sys.dm_exec_sessions AS s ON s.session_id = r.session_id
            WHERE r.blocking_session_id > 0
              AND r.blocking_session_id <> r.session_id
            ORDER BY r.wait_time DESC
            """;

    private static final String SESSION_DETAIL_QUERY = """
            SELECT s.session_id AS sessionId,
                   s.login_name AS loginName,
                   s.program_name AS programName,
                   s.host_name AS hostName,
                   s.status AS status,
                   DATEDIFF(SECOND, openTransaction.beginTime, SYSDATETIME()) AS transactionSeconds,
                   lastSql.text AS lastStatement
            FROM sys.dm_exec_sessions AS s
            OUTER APPLY (
                SELECT MIN(activeTx.transaction_begin_time) AS beginTime
                FROM sys.dm_tran_session_transactions AS sessionTx
                JOIN sys.dm_tran_active_transactions AS activeTx ON activeTx.transaction_id = sessionTx.transaction_id
                WHERE sessionTx.session_id = s.session_id
            ) AS openTransaction
            OUTER APPLY (
                SELECT TOP (1) sqlText.text
                FROM sys.dm_exec_connections AS c
                CROSS APPLY sys.dm_exec_sql_text(c.most_recent_sql_handle) AS sqlText
                WHERE c.session_id = s.session_id
            ) AS lastSql
            WHERE s.session_id = ?
            """;

    private static final String LOCK_QUERY = """
            SELECT TOP (1)
                   l.resource_type AS resourceType,
                   l.resource_database_id AS databaseId,
                   DB_NAME(l.resource_database_id) AS databaseName,
                   l.resource_associated_entity_id AS entityId,
                   l.request_mode AS requestMode
            FROM sys.dm_tran_locks AS l
            WHERE l.request_session_id = ?
              AND l.request_status = 'WAIT'
            """;

    private static final String OBJECT_LOCK_QUERY = """
            SELECT OBJECT_SCHEMA_NAME(?, ?) AS schemaName,
                   OBJECT_NAME(?, ?) AS objectName,
                   CAST(NULL AS SYSNAME) AS indexName
            """;

    private static final String HOBT_LOCK_BODY = """
            SELECT OBJECT_SCHEMA_NAME(p.object_id) AS schemaName,
                   OBJECT_NAME(p.object_id) AS objectName,
                   i.name AS indexName
            FROM sys.partitions AS p
            LEFT JOIN sys.indexes AS i ON i.object_id = p.object_id AND i.index_id = p.index_id
            WHERE p.hobt_id = %d
            """;

    private static final String KILL_TARGET_QUERY = """
            SELECT s.session_id AS sessionId,
                   s.program_name AS programName,
                   (SELECT COUNT(*)
                    FROM sys.dm_exec_requests AS r
                    WHERE r.blocking_session_id = s.session_id
                      AND r.session_id <> s.session_id) AS blockedCount
            FROM sys.dm_exec_sessions AS s
            WHERE s.session_id = ?
              AND s.is_user_process = 1
            """;

    private final ActiveConnection activeConnection;

    public LocalDateTime findServerNow() {
        return jdbc().queryForObject(SERVER_NOW_QUERY, (rs, rowNum) -> toLocal(rs.getTimestamp("serverNow")));
    }

    public List<CpuRow> findCpuHistory(int limit) {
        return jdbc().query(CPU_QUERY.formatted(limit), (rs, rowNum) -> new CpuRow(
                toLocal(rs.getTimestamp("sampledAt")),
                rs.getInt("sqlCpu"),
                rs.getInt("systemIdle")
        ));
    }

    public List<MemoryRow> findMemory() {
        return jdbc().query(MEMORY_QUERY, (rs, rowNum) -> new MemoryRow(
                toLocal(rs.getTimestamp("sampledAt")),
                rs.getLong("totalKb"),
                rs.getLong("availableKb")
        ));
    }

    public List<MemoryRow> findMemoryHistory(int limit) {
        return jdbc().query(MEMORY_HISTORY_QUERY.formatted(limit), (rs, rowNum) -> new MemoryRow(
                toLocal(rs.getTimestamp("sampledAt")),
                rs.getLong("totalKb"),
                rs.getLong("availableKb")
        ));
    }

    public SessionCountRow findSessionCount() {
        return jdbc().queryForObject(SESSION_COUNT_QUERY, (rs, rowNum) -> new SessionCountRow(
                rs.getInt("connected"),
                rs.getInt("active")
        ), PerformanceUtils.MONITOR_PROGRAM);
    }

    public List<ProcessRow> findProcesses(int minutes, int limit) {
        return jdbc().query(PROCESSES_QUERY.formatted(limit), (rs, rowNum) -> new ProcessRow(
                rs.getInt("sessionId"),
                rs.getString("programName"),
                rs.getString("loginName"),
                rs.getString("hostName"),
                rs.getString("status"),
                rs.getLong("cpuMs"),
                rs.getLong("memoryKb"),
                rs.getLong("totalCpuMs")
        ), PerformanceUtils.MONITOR_PROGRAM, minutes);
    }

    public List<QueryRow> findQueries(int minutes, int limit) {
        return jdbc().query(QUERIES_QUERY.formatted(limit), (rs, rowNum) -> new QueryRow(
                rs.getString("queryHash"),
                rs.getString("statementText"),
                rs.getLong("executions"),
                rs.getLong("totalElapsed"),
                rs.getLong("totalWorker"),
                rs.getLong("allWorker")
        ), minutes);
    }

    public AverageRow findAverage(int minutes) {
        return jdbc().queryForObject(AVERAGE_QUERY, (rs, rowNum) -> new AverageRow(
                rs.getLong("executions"),
                rs.getLong("totalElapsed"),
                rs.getInt("queryCount")
        ), minutes);
    }

    public List<RequestRow> findRequests() {
        return jdbc().query(REQUESTS_QUERY, (rs, rowNum) -> new RequestRow(
                rs.getInt("sessionId"),
                rs.getString("programName"),
                rs.getString("loginName"),
                rs.getString("hostName"),
                rs.getString("databaseName"),
                rs.getString("command"),
                rs.getString("status"),
                rs.getLong("elapsedSeconds")
        ), PerformanceUtils.MONITOR_PROGRAM);
    }

    public List<WaitRow> findWaits() {
        return jdbc().query(WAITS_QUERY, (rs, rowNum) -> new WaitRow(
                rs.getInt("sessionId"),
                rs.getInt("blockingSessionId"),
                rs.getString("loginName"),
                rs.getString("waitType"),
                rs.getLong("waitMs"),
                rs.getString("waitResource")
        ));
    }

    public Optional<SessionDetailRow> findSessionDetail(int sessionId) {
        return jdbc().query(SESSION_DETAIL_QUERY, (rs, rowNum) -> new SessionDetailRow(
                rs.getInt("sessionId"),
                rs.getString("loginName"),
                rs.getString("programName"),
                rs.getString("hostName"),
                rs.getString("status"),
                nullableLong(rs, "transactionSeconds"),
                rs.getString("lastStatement")
        ), sessionId).stream().findFirst();
    }

    public Optional<LockRow> findWaitingLock(int sessionId) {
        return jdbc().query(LOCK_QUERY, (rs, rowNum) -> new LockRow(
                rs.getString("resourceType"),
                rs.getInt("databaseId"),
                rs.getString("databaseName"),
                rs.getLong("entityId"),
                rs.getString("requestMode")
        ), sessionId).stream().findFirst();
    }

    public Optional<LockObjectRow> findObject(int databaseId, int objectId) {
        return jdbc().query(OBJECT_LOCK_QUERY, (rs, rowNum) -> new LockObjectRow(
                        rs.getString("schemaName"),
                        rs.getString("objectName"),
                        rs.getString("indexName")
                ), objectId, databaseId, objectId, databaseId).stream()
                .filter(row -> row.objectName() != null)
                .findFirst();
    }

    public Optional<LockObjectRow> findHobtObject(String database, long hobtId) {
        return jdbc().query(inDatabase(database, HOBT_LOCK_BODY.formatted(hobtId)), (rs, rowNum) -> new LockObjectRow(
                rs.getString("schemaName"),
                rs.getString("objectName"),
                rs.getString("indexName")
        )).stream().findFirst();
    }

    public Optional<KillTargetRow> findKillTarget(int sessionId) {
        return jdbc().query(KILL_TARGET_QUERY, (rs, rowNum) -> new KillTargetRow(
                rs.getInt("sessionId"),
                rs.getString("programName"),
                rs.getInt("blockedCount")
        ), sessionId).stream().findFirst();
    }

    public void killSession(int sessionId) {
        jdbc().execute("KILL " + sessionId);
    }

    private JdbcTemplate jdbc() {
        return activeConnection.jdbcTemplate();
    }

    private static String inDatabase(String database, String body) {
        return "EXEC %s.sys.sp_executesql N'%s'".formatted(
                AuditUtils.quoteName(database),
                body.replace("'", "''")
        );
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}