package grupo1.backend.logic.dto;

import java.time.Instant;
import java.util.List;

public record InstanceDTO(
        Instance instance,
        Server server,
        List<Database> databases,
        Memory memory,
        ConnectionDetails connection,
        Sessions sessions,
        LastCheck lastCheck
) {

    public record Instance(
            String name,
            String status,
            String statusLabel,
            String version,
            String uptime,
            String startedAt
    ) {
    }

    public record Server(String name, String details) {
    }

    public record Database(
            String name,
            String recoveryModel,
            String status,
            String statusLabel,
            String size,
            boolean system
    ) {
    }

    public record Memory(
            String maxServer,
            String inUse,
            Integer inUsePercent,
            String bufferAllocated,
            String bufferInUse,
            Integer bufferPercent
    ) {
    }

    public record ConnectionDetails(String host, String instance, String user) {
    }

    public record Sessions(int total, int allowed) {
    }

    public record LastCheck(Instant collectedAt, int refreshSeconds) {
    }
}
