package grupo1.backend.logic.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record BackupDTO(
        Metrics metrics,
        List<HistoryItem> history,
        List<Day> days,
        Assessment assessment,
        Policy policy
) {

    public record Metrics(LocalDateTime lastSuccessAt, String lastType, Long lastDurationMin) {
    }

    public record HistoryItem(String id, LocalDateTime startedAt, String database, String type,
                              String size, Long durationMin, String status) {
    }

    /** status: online | warning | offline | none (día sin respaldos) */
    public record Day(LocalDate date, String status) {
    }

    /** level: online | warning | offline */
    public record Assessment(String level, String title, String detail, String recommendation) {
    }

    public record Policy(List<PolicyRow> rows, String recoveryModel, String destination, String verification) {
    }

    public record PolicyRow(String type, String frequency, String retention) {
    }
}
