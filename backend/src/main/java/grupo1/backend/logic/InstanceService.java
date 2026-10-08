package grupo1.backend.logic;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import grupo1.backend.data.ActiveConnection;
import grupo1.backend.data.InstanceRepository;
import grupo1.backend.logic.dto.InstanceDTO;
import grupo1.backend.logic.utils.InstanceUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InstanceService {

    private static final long UNLIMITED_MEMORY_MB = 2_147_483_647L;
    private static final String DEFAULT_INSTANCE_NAME = "MSSQLSERVER";
    private static final String STATUS_ONLINE = "online";
    private static final String STATUS_WARNING = "warning";
    private static final String STATUS_OFFLINE = "offline";
    private static final DateTimeFormatter START_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private record DatabaseStatus(String code, String label) {
    }

    private final InstanceRepository instanceRepository;
    private final ActiveConnection activeConnection;

    public InstanceDTO overview() {
        Connection profile = activeConnection.profile().orElseThrow(NoActiveConnectionException::new);
        InstanceRepository.InstanceRow instance = instanceRepository.findInstance();
        InstanceRepository.MemoryRow memory = instanceRepository.findMemory();
        InstanceRepository.SessionRow sessions = instanceRepository.findSessions();
        List<InstanceDTO.Database> databases = instanceRepository.findDatabases().stream()
                .map(InstanceService::toDatabase)
                .toList();
        return new InstanceDTO(
                toInstance(instance, databases),
                toServer(instance),
                databases,
                toMemory(memory),
                toConnectionDetails(profile, instance),
                new InstanceDTO.Sessions(sessions.userSessions(), sessions.maxConnections()),
                new InstanceDTO.LastCheck(Instant.now(), profile.refreshSeconds())
        );
    }

    private static InstanceDTO.Instance toInstance(InstanceRepository.InstanceRow row, List<InstanceDTO.Database> databases) {
        long needAttention = databases.stream()
                .filter(database -> !STATUS_ONLINE.equals(database.status()))
                .count();
        String status = needAttention == 0 ? STATUS_ONLINE : STATUS_WARNING;
        String statusLabel = needAttention == 0
                ? "ONLINE · En ejecución"
                : "ONLINE · %d base(s) requieren atención".formatted(needAttention);
        String year = InstanceUtils.marketingYear(row.productVersion());
        String product = StringUtils.hasText(year) ? "Microsoft SQL Server " + year : "Microsoft SQL Server";
        String version = "%s %s · %s (%s)".formatted(product, row.edition(), row.productVersion(), row.productLevel());
        return new InstanceDTO.Instance(
                resolveInstanceName(row),
                status,
                statusLabel,
                version,
                InstanceUtils.formatUptime(row.uptimeSeconds()),
                row.startTime().format(START_FORMAT)
        );
    }

    private static InstanceDTO.Server toServer(InstanceRepository.InstanceRow row) {
        String system = StringUtils.hasText(row.hostDistribution()) ? row.hostDistribution() : "Servidor";
        String details = "%s · %d CPU · %s RAM".formatted(
                system,
                row.cpuCount(),
                InstanceUtils.formatMegabytes((double) row.physicalMemoryMb())
        );
        return new InstanceDTO.Server(row.machineName(), details);
    }

    private static InstanceDTO.Memory toMemory(InstanceRepository.MemoryRow row) {
        boolean limited = row.maxServerMemoryMb() != null && row.maxServerMemoryMb() < UNLIMITED_MEMORY_MB;
        Long effectiveMax = limited ? row.maxServerMemoryMb() : row.targetServerMemoryMb();
        return new InstanceDTO.Memory(
                InstanceUtils.formatMegabytes(toDouble(effectiveMax)),
                InstanceUtils.formatMegabytes(toDouble(row.processMemoryMb())),
                InstanceUtils.percent(row.processMemoryMb(), effectiveMax),
                InstanceUtils.formatMegabytes(toDouble(row.totalServerMemoryMb())),
                InstanceUtils.formatMegabytes(toDouble(row.databaseCacheMb())),
                InstanceUtils.percent(row.databaseCacheMb(), row.totalServerMemoryMb())
        );
    }

    private static InstanceDTO.ConnectionDetails toConnectionDetails(Connection profile, InstanceRepository.InstanceRow row) {
        String host = profile.port() != null ? profile.host() + ":" + profile.port() : profile.host();
        return new InstanceDTO.ConnectionDetails(host, resolveInstanceName(row), row.loginName());
    }

    private static InstanceDTO.Database toDatabase(InstanceRepository.DatabaseRow row) {
        DatabaseStatus status = resolveDatabaseStatus(row);
        return new InstanceDTO.Database(
                row.name(),
                row.recoveryModel(),
                status.code(),
                status.label(),
                InstanceUtils.formatMegabytes(row.sizeMb()),
                row.system()
        );
    }

    private static DatabaseStatus resolveDatabaseStatus(InstanceRepository.DatabaseRow row) {
        String state = row.stateDesc() == null ? "" : row.stateDesc();
        return switch (state) {
            case "ONLINE" -> row.readOnly()
                    ? new DatabaseStatus(STATUS_ONLINE, "ONLINE · Solo lectura")
                    : new DatabaseStatus(STATUS_ONLINE, "ONLINE");
            case "RESTORING", "RECOVERING" -> new DatabaseStatus(STATUS_WARNING, state);
            default -> new DatabaseStatus(STATUS_OFFLINE, state.isEmpty() ? "DESCONOCIDO" : state);
        };
    }

    private static String resolveInstanceName(InstanceRepository.InstanceRow row) {
        return StringUtils.hasText(row.instanceName()) ? row.instanceName() : DEFAULT_INSTANCE_NAME;
    }

    private static Double toDouble(Long value) {
        return value == null ? null : value.doubleValue();
    }
}
