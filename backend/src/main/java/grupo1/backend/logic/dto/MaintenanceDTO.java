package grupo1.backend.logic.dto;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record MaintenanceDTO(String profileId, String profileName, List<String> databases,
                             String database, List<Table> tables, List<Module> objects,
                             List<String> warnings, Instant checkedAt) {
    public record Table(int objectId, String schema, String name, int statistics,
                        String lastUpdated, Long modifications, int datedStatistics) {
        public Table(int objectId, String schema, String name, int statistics,
                     String lastUpdated, Long modifications) {
            this(objectId, schema, name, statistics, lastUpdated, modifications,
                    lastUpdated == null ? 0 : statistics);
        }
    }

    public record Module(int objectId, String schema, String name, String type,
                         String problem, boolean repairable) {
    }

    public record Request(@NotBlank String profileId, @NotBlank @Size(max = 128) String database,
                          @Positive int objectId, @NotBlank @Size(max = 128) String schema,
                          @NotBlank @Size(max = 128) String name,
                          @NotNull @AssertTrue Boolean confirmed, Boolean fullScan) {
    }

    public record Result(boolean ok, String action, String database, String object,
                         String message, Instant finishedAt, String status) {
        public Result(boolean ok, String action, String database, String object,
                      String message, Instant finishedAt) {
            this(ok, action, database, object, message, finishedAt, ok ? "verified" : "failed");
        }
    }
}
