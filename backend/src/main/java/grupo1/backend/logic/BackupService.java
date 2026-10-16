package grupo1.backend.logic;

import grupo1.backend.data.ActiveConnection;
import grupo1.backend.data.BackupRepository;
import grupo1.backend.logic.dto.BackupDTO;
import grupo1.backend.logic.dto.BackupRunDTO;
import grupo1.backend.logic.utils.BackupUtils;
import grupo1.backend.logic.utils.InstanceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class BackupService {

    private static final String STATUS_ONLINE = "online";
    private static final String STATUS_WARNING = "warning";
    private static final String STATUS_OFFLINE = "offline";
    private static final String STATUS_NONE = "none";

    private static final int SEVERITY_OK = 0;
    private static final int SEVERITY_MODERATE = 1;
    private static final int SEVERITY_HIGH = 2;

    private static final int HISTORY_LIMIT = 10;
    private static final int DAYS_WINDOW = 14;
    private static final int FAILURE_LOOKBACK_DAYS = 30;
    private static final long FULL_WARNING_DAYS = 7;
    private static final long FULL_CRITICAL_DAYS = 14;
    private static final long STALE_SUCCESS_HOURS = 48;

    private static final List<String> TYPE_ORDER = List.of("D", "I", "L");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private record Failure(LocalDateTime at, String database, String type) {
    }

    private final BackupRepository backupRepository;
    private final ActiveConnection activeConnection;

    /* ------------------------- GET /overview ------------------------- */

    public BackupDTO overview(String database) {
        activeConnection.profile().orElseThrow(NoActiveConnectionException::new);
        String scope = BackupUtils.normalizeDatabase(database);
        List<String> warnings = new ArrayList<>();

        LocalDateTime now = backupRepository.findServerNow();
        List<BackupRepository.BackupRow> recent = backupRepository.findRecentBackups(scope, HISTORY_LIMIT);
        Optional<BackupRepository.BackupRow> lastSuccess = backupRepository.findLastSuccess(scope, false);
        Optional<BackupRepository.BackupRow> lastFull = backupRepository.findLastSuccess(scope, true);
        List<BackupRepository.DailyRow> daily = backupRepository.findDailySummary(scope, DAYS_WINDOW);
        List<Failure> failures = resolveFailures(scope, now, warnings);
        List<String> withoutFull = backupRepository.findDatabasesWithoutFull(scope);

        List<BackupDTO.HistoryItem> history = Stream.concat(
                        recent.stream().map(BackupService::toHistoryItem),
                        failureItems(failures))
                .sorted(Comparator.comparing(BackupDTO.HistoryItem::startedAt).reversed())
                .limit(HISTORY_LIMIT)
                .toList();

        BackupDTO.Metrics metrics = lastSuccess
                .map(row -> new BackupDTO.Metrics(
                        row.startedAt(),
                        BackupUtils.typeName(row.type()),
                        BackupUtils.toMinutes(row.durationSeconds())))
                .orElse(new BackupDTO.Metrics(null, null, null));

        return new BackupDTO(
                metrics,
                history,
                toDays(now, daily, failures),
                toAssessment(now, lastSuccess, lastFull, failures, daily, withoutFull),
                toPolicy(scope, now),
                new BackupDTO.Scan(backupRepository.findBackupableDatabases(scope), warnings)
        );
    }

    private List<Failure> resolveFailures(String scope, LocalDateTime now, List<String> warnings) {
        List<BackupRepository.FailureRow> rows;
        try {
            rows = backupRepository.findBackupFailures();
        } catch (DataAccessException e) {
            warnings.add("No se pudo leer el log de errores de SQL Server; los respaldos fallidos no se informan: %s"
                    .formatted(NestedExceptionUtils.getMostSpecificCause(e).getMessage()));
            return List.of();
        }
        LocalDateTime since = now.minusDays(FAILURE_LOOKBACK_DAYS);
        return rows.stream()
                .filter(row -> row.at() != null && row.at().isAfter(since))
                .map(row -> BackupUtils.parseFailure(row.text())
                        .map(parsed -> new Failure(row.at(), parsed.database(), parsed.type())))
                .flatMap(Optional::stream)
                .filter(failure -> inScope(failure, scope))
                .toList();
    }

    private static boolean inScope(Failure failure, String scope) {
        return scope == null
                ? !"tempdb".equalsIgnoreCase(failure.database())
                : scope.equalsIgnoreCase(failure.database());
    }

    private static BackupDTO.HistoryItem toHistoryItem(BackupRepository.BackupRow row) {
        return new BackupDTO.HistoryItem(
                "bs-" + row.id(),
                row.startedAt(),
                row.database(),
                BackupUtils.typeName(row.type()),
                InstanceUtils.formatMegabytes(BackupUtils.toMegabytes(row.sizeBytes())),
                BackupUtils.toMinutes(row.durationSeconds()),
                row.damaged() ? STATUS_WARNING : STATUS_ONLINE
        );
    }

    private static Stream<BackupDTO.HistoryItem> failureItems(List<Failure> failures) {
        return IntStream.range(0, failures.size()).mapToObj(index -> {
            Failure failure = failures.get(index);
            return new BackupDTO.HistoryItem(
                    "err-" + index, failure.at(), failure.database(), failure.type(), null, null, STATUS_OFFLINE);
        });
    }

    private static List<BackupDTO.Day> toDays(LocalDateTime now,
                                              List<BackupRepository.DailyRow> daily,
                                              List<Failure> failures) {
        LocalDate today = now.toLocalDate();
        Map<LocalDate, BackupRepository.DailyRow> byDay = daily.stream()
                .collect(Collectors.toMap(BackupRepository.DailyRow::day, Function.identity(), (a, b) -> a));
        Set<LocalDate> failedDays = failures.stream()
                .map(failure -> failure.at().toLocalDate())
                .collect(Collectors.toSet());

        return IntStream.range(0, DAYS_WINDOW)
                .mapToObj(index -> today.minusDays(DAYS_WINDOW - 1L - index))
                .map(day -> {
                    BackupRepository.DailyRow row = byDay.get(day);
                    String status;
                    if (failedDays.contains(day)) {
                        status = STATUS_OFFLINE;
                    } else if (row != null && row.damaged() > 0) {
                        status = STATUS_WARNING;
                    } else if (row != null && row.total() > 0) {
                        status = STATUS_ONLINE;
                    } else {
                        status = STATUS_NONE;
                    }
                    return new BackupDTO.Day(day, status);
                })
                .toList();
    }

    private static BackupDTO.Assessment toAssessment(LocalDateTime now,
                                                     Optional<BackupRepository.BackupRow> lastSuccess,
                                                     Optional<BackupRepository.BackupRow> lastFull,
                                                     List<Failure> failures,
                                                     List<BackupRepository.DailyRow> daily,
                                                     List<String> withoutFull) {
        BackupRepository.BackupRow success = lastSuccess.orElse(null);
        BackupRepository.BackupRow full = lastFull.orElse(null);
        List<String> findings = new ArrayList<>();
        int severity = SEVERITY_OK;

        if (full == null) {
            severity = SEVERITY_HIGH;
            findings.add("No existe ningún respaldo completo exitoso registrado en esta instancia.");
        } else {
            long fullDays = Duration.between(full.startedAt(), now).toDays();
            if (fullDays > FULL_CRITICAL_DAYS) {
                severity = SEVERITY_HIGH;
            } else if (fullDays > FULL_WARNING_DAYS) {
                severity = SEVERITY_MODERATE;
            }
            if (fullDays > FULL_WARNING_DAYS) {
                findings.add("El último respaldo completo exitoso tiene %d días.".formatted(fullDays));
            }

            if (!withoutFull.isEmpty()) {
                severity = Math.max(severity, SEVERITY_MODERATE);
                String names = withoutFull.stream().limit(5).collect(Collectors.joining(", "))
                        + (withoutFull.size() > 5 ? "…" : "");
                findings.add("%d base(s) sin respaldo completo exitoso: %s.".formatted(withoutFull.size(), names));
            }
        }

        LocalDateTime windowStart = now.minusDays(DAYS_WINDOW);
        List<Failure> recentFailures = failures.stream()
                .filter(failure -> failure.at().isAfter(windowStart))
                .sorted(Comparator.comparing(Failure::at).reversed())
                .toList();
        if (!recentFailures.isEmpty()) {
            severity = Math.max(severity, SEVERITY_MODERATE);
            findings.add("Se registraron %d respaldo(s) fallido(s) en los últimos %d días (el último el %s)."
                    .formatted(recentFailures.size(), DAYS_WINDOW, DAY_FORMAT.format(recentFailures.get(0).at())));
        }

        int damaged = daily.stream().mapToInt(BackupRepository.DailyRow::damaged).sum();
        if (damaged > 0) {
            severity = Math.max(severity, SEVERITY_MODERATE);
            findings.add("%d respaldo(s) quedaron marcados como dañados en los últimos %d días."
                    .formatted(damaged, DAYS_WINDOW));
        }

        if (success != null) {
            long hours = Duration.between(success.startedAt(), now).toHours();
            if (hours > STALE_SUCCESS_HOURS) {
                severity = Math.max(severity, SEVERITY_MODERATE);
                findings.add("Han pasado %d h desde el último respaldo exitoso.".formatted(hours));
            }
        }

        if (severity == SEVERITY_OK && full != null) {
            return new BackupDTO.Assessment(
                    STATUS_ONLINE,
                    "Respaldos en buen estado",
                    "El último respaldo completo exitoso fue %s y no hay fallos en los últimos %d días."
                            .formatted(BackupUtils.formatAge(Duration.between(full.startedAt(), now).toHours()),
                                    DAYS_WINDOW),
                    null);
        }

        if (severity == SEVERITY_HIGH) {
            return new BackupDTO.Assessment(
                    STATUS_OFFLINE,
                    "Riesgo alto detectado",
                    String.join(" ", findings),
                    "Ejecute un respaldo FULL de inmediato: la recuperación ante un fallo no está garantizada.");
        }

        return new BackupDTO.Assessment(
                STATUS_WARNING,
                "Riesgo moderado detectado",
                String.join(" ", findings),
                "Ejecute un respaldo FULL y revise la causa de los fallos para reducir el riesgo.");
    }

    private BackupDTO.Policy toPolicy(String scope, LocalDateTime now) {
        List<BackupDTO.PolicyRow> rows = backupRepository.findTypeStats(scope).stream()
                .filter(stats -> TYPE_ORDER.contains(stats.type()))
                .sorted(Comparator.comparingInt(stats -> TYPE_ORDER.indexOf(stats.type())))
                .map(stats -> new BackupDTO.PolicyRow(
                        BackupUtils.typeName(stats.type()),
                        BackupUtils.formatFrequency(stats.recentCount(), stats.recentFirst(), stats.recentLast()),
                        BackupUtils.formatRetention(stats.oldest(), now)))
                .toList();

        List<String> models = backupRepository.findRecoveryModels(scope);
        String recoveryModel = models.isEmpty() ? null : models.size() == 1 ? models.get(0) : "MIXTO";

        Optional<BackupRepository.MediaRow> media = backupRepository.findLatestMedia(scope);
        String destination = media.map(m -> BackupUtils.directoryOf(m.device())).orElse(null);
        String verification = media.map(m -> m.checksum() ? "CHECKSUM" : "Sin CHECKSUM").orElse(null);

        return new BackupDTO.Policy(rows, recoveryModel, destination, verification);
    }

    /* ------------------------- POST /full ------------------------- */

    public BackupRunDTO runFull(String database) {
        activeConnection.profile().orElseThrow(NoActiveConnectionException::new);
        String scope = BackupUtils.normalizeDatabase(database);

        List<String> databases = backupRepository.findBackupableDatabases(scope);
        if (databases.isEmpty()) {
            throw new BackupFailedException(scope == null
                    ? "No hay bases de datos de usuario en línea para respaldar."
                    : "La base de datos '%s' no existe, no es de usuario o no está en línea.".formatted(scope));
        }

        LocalDateTime now = backupRepository.findServerNow();
        String defaultDirectory = null;
        List<BackupRunDTO.Result> results = new ArrayList<>();

        for (String name : databases) {
            String directory = backupRepository.findLatestMedia(name)
                    .map(media -> BackupUtils.directoryOf(media.device()))
                    .orElse(null);
            if (directory == null) {
                if (defaultDirectory == null) {
                    defaultDirectory = backupRepository.findDefaultBackupDirectory();
                }
                directory = defaultDirectory;
            }
            if (directory == null) {
                results.add(new BackupRunDTO.Result(name, false, null,
                        "No se pudo determinar el directorio de respaldos de la instancia."));
                continue;
            }

            String file = BackupUtils.joinPath(directory,
                    "%s_FULL_%s.bak".formatted(BackupUtils.safeFileName(name), FILE_STAMP.format(now)));
            try {
                backupRepository.backupFull(name, file);
                results.add(new BackupRunDTO.Result(name, true, file, "Respaldo completado"));
            } catch (DataAccessException e) {
                results.add(new BackupRunDTO.Result(name, false, file,
                        NestedExceptionUtils.getMostSpecificCause(e).getMessage()));
            }
        }
        return new BackupRunDTO(results);
    }
}
