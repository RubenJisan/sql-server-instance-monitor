package grupo1.backend.logic.dto;

import java.time.Instant;
import java.util.List;

public record PerformanceDTO(
        Instance instance,
        Range range,
        Kpis kpis,
        Chart chart,
        List<Process> processes,
        List<Query> queries,
        Sessions sessions,
        Blocking blocking,
        LastCheck lastCheck,
        List<String> warnings
) {

    public record Instance(String name, String serverName) {
    }

    public record Range(String code, String label) {
    }

    public record Kpi(String value, String detail, String status) {
    }

    public record Kpis(Kpi cpu, Kpi activeSessions, Kpi blockedSessions, Kpi averageDuration) {
    }

    public record Chart(List<ChartPoint> points) {
    }

    public record ChartPoint(String time, Double cpuPercent, Double memoryPercent) {
    }

    public record Process(
            int sessionId,
            String name,
            String detail,
            String cpu,
            double cpuPercent,
            String status
    ) {
    }

    public record Query(
            String hash,
            String statement,
            String fullStatement,
            long executions,
            String average,
            double averageMs,
            String averageStatus,
            String total,
            double totalMs,
            double cpuPercent,
            String cpuStatus
    ) {
    }

    public record Sessions(int active, int connected, List<Session> items) {
    }

    public record Session(
            int sessionId,
            String program,
            String login,
            String host,
            String database,
            String operation,
            String duration,
            long durationSeconds,
            String status
    ) {
    }

    public record Blocking(int blockedCount, int rootCount, List<Chain> chains) {
    }

    public record Chain(
            Blocker blocker,
            List<Waiter> waiters,
            String object,
            String resource,
            String waitType,
            String maxWait,
            String transactionAge,
            String transactionStatus,
            String lastStatement
    ) {
    }

    public record Blocker(int sessionId, String login, String program, String host, String status) {
    }

    public record Waiter(int sessionId, String login, String waitTime, String waitType) {
    }

    public record LastCheck(Instant collectedAt, int refreshSeconds) {
    }
}