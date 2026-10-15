package grupo1.backend.logic;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import grupo1.backend.data.ActiveConnection;
import grupo1.backend.data.InstanceRepository;
import grupo1.backend.data.PerformanceHistory;
import grupo1.backend.data.PerformanceHistory.Sample;
import grupo1.backend.data.PerformanceRepository;
import grupo1.backend.data.PerformanceRepository.AverageRow;
import grupo1.backend.data.PerformanceRepository.CpuRow;
import grupo1.backend.data.PerformanceRepository.KillTargetRow;
import grupo1.backend.data.PerformanceRepository.LockObjectRow;
import grupo1.backend.data.PerformanceRepository.LockRow;
import grupo1.backend.data.PerformanceRepository.MemoryRow;
import grupo1.backend.data.PerformanceRepository.ProcessRow;
import grupo1.backend.data.PerformanceRepository.QueryRow;
import grupo1.backend.data.PerformanceRepository.RequestRow;
import grupo1.backend.data.PerformanceRepository.SessionCountRow;
import grupo1.backend.data.PerformanceRepository.SessionDetailRow;
import grupo1.backend.data.PerformanceRepository.WaitRow;
import grupo1.backend.logic.dto.PerformanceDTO;
import grupo1.backend.logic.dto.PerformanceExportDTO;
import grupo1.backend.logic.dto.SessionKillDTO;
import grupo1.backend.logic.utils.InstanceUtils;
import grupo1.backend.logic.utils.PerformanceUtils;
import grupo1.backend.logic.utils.PerformanceUtils.TimeRange;
import grupo1.backend.logic.utils.ReportUtils;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceService {

    private static final String DEFAULT_INSTANCE_NAME = "MSSQLSERVER";
    private static final String STATUS_ONLINE = "online";
    private static final String STATUS_WARNING = "warning";
    private static final String STATUS_OFFLINE = "offline";

    private static final int CPU_HISTORY_LIMIT = 300;
    private static final int MEMORY_HISTORY_LIMIT = 500;
    private static final int PROCESS_LIMIT = 5;
    private static final int QUERY_LIMIT = 10;
    private static final int CHAIN_LIMIT = 5;
    private static final int STATEMENT_PREVIEW_LENGTH = 140;
    private static final int STATEMENT_FULL_LENGTH = 4000;
    private static final long SAMPLE_INTERVAL_MILLIS = 60_000;

    private static final int CPU_WARNING = 50;
    private static final int CPU_CRITICAL = 80;
    private static final double AVERAGE_WARNING_MS = 100;
    private static final double AVERAGE_CRITICAL_MS = 1000;
    private static final long SESSION_WARNING_SECONDS = 60;
    private static final long SESSION_CRITICAL_SECONDS = 300;
    private static final double SHARE_WARNING = 25;
    private static final double SHARE_CRITICAL = 50;
    private static final double QUERY_CPU_WARNING = 20;
    private static final double QUERY_CPU_CRITICAL = 50;
    private static final long TRANSACTION_WARNING_SECONDS = 60;

    private static final String OBJECT_RESOURCE = "OBJECT";
    private static final Set<String> HOBT_RESOURCES = Set.of("KEY", "PAGE", "RID", "HOBT");

    private static final String SECTION_QUERIES = "queries";
    private static final String SECTION_SESSIONS = "sessions";
    private static final String SECTION_BLOCKING = "blocking";
    private static final String SECTION_CHART = "chart";
    private static final Set<String> ALL_SECTIONS = Set.of(SECTION_QUERIES, SECTION_SESSIONS, SECTION_BLOCKING, SECTION_CHART);
    private static final Set<String> DEFAULT_SECTIONS = Set.of(SECTION_QUERIES, SECTION_SESSIONS, SECTION_BLOCKING);
    private static final Map<String, String> EXPORT_TYPES = Map.of(
            "pdf", "application/pdf",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "csv", "text/csv;charset=UTF-8"
    );
    private static final String CPU_COLOR = "#38bdf8";
    private static final String MEMORY_COLOR = "#a78bfa";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter REPORT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private record TimedValue(LocalDateTime at, double value) {
    }

    private record LockTarget(String object, String resource) {
    }

    private final PerformanceRepository performanceRepository;
    private final PerformanceHistory performanceHistory;
    private final InstanceRepository instanceRepository;
    private final ActiveConnection activeConnection;

    public PerformanceDTO overview(String requestedRange) {
        TimeRange range = PerformanceUtils.parseRange(requestedRange);
        Connection profile = activeConnection.profile().orElseThrow(NoActiveConnectionException::new);
        InstanceRepository.InstanceRow instance = instanceRepository.findInstance();
        LocalDateTime now = performanceRepository.findServerNow();
        List<String> warnings = new ArrayList<>();

        List<CpuRow> cpuHistory = attempt("el historial de CPU",
                () -> performanceRepository.findCpuHistory(CPU_HISTORY_LIMIT), warnings);
        List<MemoryRow> memoryNow = attempt("el uso de memoria", performanceRepository::findMemory, warnings);
        List<MemoryRow> memoryHistory = attempt("el historial de memoria",
                () -> performanceRepository.findMemoryHistory(MEMORY_HISTORY_LIMIT), warnings);
        List<ProcessRow> processes = attempt("los procesos de mayor consumo",
                () -> performanceRepository.findProcesses(range.minutes(), PROCESS_LIMIT), warnings);
        List<QueryRow> queries = attempt("las consultas de mayor consumo",
                () -> performanceRepository.findQueries(range.minutes(), QUERY_LIMIT), warnings);
        List<AverageRow> average = attempt("el tiempo promedio de ejecución",
                () -> List.of(performanceRepository.findAverage(range.minutes())), warnings);
        SessionCountRow sessionCount = performanceRepository.findSessionCount();
        List<RequestRow> requests = performanceRepository.findRequests();
        PerformanceDTO.Blocking blocking = toBlocking(performanceRepository.findWaits());

        Double currentCpu = cpuHistory.stream().findFirst().map(row -> (double) row.sqlCpu()).orElse(null);
        Double currentMemory = memoryNow.stream().findFirst().map(PerformanceService::memoryPercent).orElse(null);
        performanceHistory.add(profile.id(), new Sample(now, currentCpu, currentMemory));
        List<Sample> samples = performanceHistory.findSince(profile.id(), now.minusMinutes(range.minutes()));
        List<MemoryRow> memoryRows = Stream.concat(memoryHistory.stream(), memoryNow.stream()).toList();

        return new PerformanceDTO(
                new PerformanceDTO.Instance(resolveInstanceName(instance), instance.machineName()),
                new PerformanceDTO.Range(range.code(), range.label()),
                new PerformanceDTO.Kpis(
                        toCpuKpi(now, cpuHistory),
                        new PerformanceDTO.Kpi(
                                String.valueOf(sessionCount.active()),
                                "de %d conectadas".formatted(sessionCount.connected()),
                                null),
                        toBlockedKpi(blocking),
                        toAverageKpi(average)
                ),
                toChart(range, now, cpuHistory, memoryRows, samples),
                processes.stream().map(PerformanceService::toProcess).toList(),
                queries.stream().map(PerformanceService::toQuery).toList(),
                new PerformanceDTO.Sessions(
                        sessionCount.active(),
                        sessionCount.connected(),
                        requests.stream().map(PerformanceService::toSession).toList()),
                blocking,
                new PerformanceDTO.LastCheck(Instant.now(), profile.refreshSeconds()),
                warnings
        );
    }

    public SessionKillDTO kill(int sessionId) {
        activeConnection.profile().orElseThrow(NoActiveConnectionException::new);
        KillTargetRow target = performanceRepository.findKillTarget(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "La sesión %d no existe o no es una sesión de usuario.".formatted(sessionId)));
        if (PerformanceUtils.MONITOR_PROGRAM.equals(target.programName())) {
            throw new IllegalArgumentException(
                    "La sesión %d pertenece a DBWatch y no se puede terminar.".formatted(sessionId));
        }
        if (target.blockedCount() == 0) {
            throw new IllegalArgumentException(
                    "La sesión %d ya no está bloqueando a otras sesiones.".formatted(sessionId));
        }
        try {
            performanceRepository.killSession(sessionId);
        } catch (DataAccessException e) {
            String reason = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
            throw new IllegalStateException(
                    "No se pudo terminar la sesión %d: %s Verifique que el login de monitoreo tenga el permiso ALTER ANY CONNECTION."
                            .formatted(sessionId, reason));
        }
        return new SessionKillDTO(sessionId, "KILL %d ejecutado · bloqueo liberado".formatted(sessionId));
    }

    public PerformanceExportDTO export(String format, String range, List<String> sections) {
        String type = format == null || format.isBlank() ? "pdf" : format.trim().toLowerCase(Locale.ROOT);
        if (!EXPORT_TYPES.containsKey(type)) {
            throw new IllegalArgumentException("Formato no válido: %s. Use pdf, xlsx o csv.".formatted(format));
        }
        Set<String> included = resolveSections(sections);
        PerformanceDTO data = overview(range);
        ReportUtils.Report report = toReport(data, included);
        byte[] content = switch (type) {
            case "pdf" -> ReportUtils.toPdf(report);
            case "xlsx" -> ReportUtils.toXlsx(report);
            default -> ReportUtils.toCsv(report);
        };
        String fileName = "Reporte_Rendimiento_%s_%s.%s".formatted(
                PerformanceUtils.safeFileName(data.instance().name()),
                LocalDateTime.now().format(FILE_STAMP),
                type);
        return new PerformanceExportDTO(fileName, EXPORT_TYPES.get(type), content);
    }

    @Scheduled(fixedDelay = SAMPLE_INTERVAL_MILLIS, initialDelay = SAMPLE_INTERVAL_MILLIS)
    public void collectSample() {
        activeConnection.profile().ifPresent(profile -> {
            try {
                LocalDateTime now = performanceRepository.findServerNow();
                Double cpu = performanceRepository.findCpuHistory(1).stream()
                        .findFirst()
                        .map(row -> (double) row.sqlCpu())
                        .orElse(null);
                Double memory = performanceRepository.findMemory().stream()
                        .findFirst()
                        .map(PerformanceService::memoryPercent)
                        .orElse(null);
                performanceHistory.add(profile.id(), new Sample(now, cpu, memory));
            } catch (DataAccessException | NoActiveConnectionException e) {
                log.debug("No se pudo registrar la muestra de rendimiento: {}", e.getMessage());
            }
        });
    }

    private static <T> List<T> attempt(String section, Supplier<List<T>> query, List<String> warnings) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            String reason = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
            warnings.add("No se pudo obtener %s: %s".formatted(section, reason));
            return List.of();
        }
    }

    private static String resolveInstanceName(InstanceRepository.InstanceRow row) {
        return StringUtils.hasText(row.instanceName()) ? row.instanceName() : DEFAULT_INSTANCE_NAME;
    }

    private static Double memoryPercent(MemoryRow row) {
        if (row.totalKb() <= 0) {
            return null;
        }
        return PerformanceUtils.percent(row.totalKb() - row.availableKb(), row.totalKb());
    }

    private static PerformanceDTO.Kpi toCpuKpi(LocalDateTime now, List<CpuRow> history) {
        if (history.isEmpty()) {
            return new PerformanceDTO.Kpi(null, "Sin datos del ring buffer", null);
        }
        int current = history.getFirst().sqlCpu();
        OptionalDouble lastHour = averageCpu(history, now.minusMinutes(60), now);
        OptionalDouble previousHour = averageCpu(history, now.minusMinutes(120), now.minusMinutes(60));
        String detail = null;
        if (lastHour.isPresent() && previousHour.isPresent()) {
            detail = PerformanceUtils.trend(Math.round(lastHour.getAsDouble() - previousHour.getAsDouble()), "hora anterior");
        } else if (lastHour.isPresent()) {
            detail = "Promedio última hora: %d%%".formatted(Math.round(lastHour.getAsDouble()));
        }
        return new PerformanceDTO.Kpi(current + "%", detail, cpuStatus(current));
    }

    private static OptionalDouble averageCpu(List<CpuRow> history, LocalDateTime from, LocalDateTime to) {
        return history.stream()
                .filter(row -> row.sampledAt().isAfter(from) && !row.sampledAt().isAfter(to))
                .mapToInt(CpuRow::sqlCpu)
                .average();
    }

    private static String cpuStatus(int cpu) {
        if (cpu >= CPU_CRITICAL) {
            return STATUS_OFFLINE;
        }
        return cpu >= CPU_WARNING ? STATUS_WARNING : STATUS_ONLINE;
    }

    private static PerformanceDTO.Kpi toBlockedKpi(PerformanceDTO.Blocking blocking) {
        if (blocking.blockedCount() == 0) {
            return new PerformanceDTO.Kpi("0", "Sin bloqueos", STATUS_ONLINE);
        }
        return new PerformanceDTO.Kpi(
                String.valueOf(blocking.blockedCount()),
                "%d bloqueador(es) raíz".formatted(blocking.rootCount()),
                STATUS_OFFLINE);
    }

    private static PerformanceDTO.Kpi toAverageKpi(List<AverageRow> rows) {
        Optional<AverageRow> row = rows.stream().findFirst().filter(value -> value.executions() > 0);
        if (row.isEmpty()) {
            return new PerformanceDTO.Kpi(null, "Sin consultas en el rango", null);
        }
        AverageRow value = row.get();
        double averageMs = value.totalElapsedMicros() / 1000.0 / value.executions();
        String detail = "%s ejecuciones · %d consultas".formatted(
                PerformanceUtils.formatCount(value.executions()), value.queryCount());
        return new PerformanceDTO.Kpi(PerformanceUtils.formatMilliseconds(averageMs), detail, averageStatus(averageMs));
    }

    private static String averageStatus(double milliseconds) {
        if (milliseconds >= AVERAGE_CRITICAL_MS) {
            return STATUS_OFFLINE;
        }
        return milliseconds >= AVERAGE_WARNING_MS ? STATUS_WARNING : STATUS_ONLINE;
    }

    private static PerformanceDTO.Chart toChart(
            TimeRange range,
            LocalDateTime now,
            List<CpuRow> cpuHistory,
            List<MemoryRow> memoryRows,
            List<Sample> samples
    ) {
        List<TimedValue> cpu = Stream.concat(
                cpuHistory.stream().map(row -> new TimedValue(row.sampledAt(), row.sqlCpu())),
                samples.stream()
                        .filter(sample -> sample.cpuPercent() != null)
                        .map(sample -> new TimedValue(sample.sampledAt(), sample.cpuPercent()))
        ).toList();
        List<TimedValue> memory = Stream.concat(
                memoryRows.stream()
                        .filter(row -> row.sampledAt() != null)
                        .map(row -> Optional.ofNullable(memoryPercent(row))
                                .map(percent -> new TimedValue(row.sampledAt(), percent)))
                        .flatMap(Optional::stream),
                samples.stream()
                        .filter(sample -> sample.memoryPercent() != null)
                        .map(sample -> new TimedValue(sample.sampledAt(), sample.memoryPercent()))
        ).toList();

        LocalDateTime start = now.minusMinutes(range.minutes());
        int buckets = range.minutes() / range.bucketMinutes();
        List<PerformanceDTO.ChartPoint> points = IntStream.range(0, buckets)
                .mapToObj(index -> {
                    LocalDateTime from = start.plusMinutes((long) index * range.bucketMinutes());
                    LocalDateTime to = from.plusMinutes(range.bucketMinutes());
                    return new PerformanceDTO.ChartPoint(
                            to.format(TIME_FORMAT),
                            bucketAverage(cpu, from, to),
                            bucketAverage(memory, from, to));
                })
                .toList();
        return new PerformanceDTO.Chart(points);
    }

    private static Double bucketAverage(List<TimedValue> values, LocalDateTime from, LocalDateTime to) {
        OptionalDouble average = values.stream()
                .filter(value -> value.at().isAfter(from) && !value.at().isAfter(to))
                .mapToDouble(TimedValue::value)
                .average();
        return average.isPresent() ? PerformanceUtils.round(average.getAsDouble()) : null;
    }

    private static PerformanceDTO.Process toProcess(ProcessRow row) {
        double share = PerformanceUtils.percent(row.cpuMs(), row.totalCpuMs());
        String detail = "%s · CPU %s · MEM %s".formatted(
                row.loginName(),
                PerformanceUtils.formatCpuTime(row.cpuMs()),
                InstanceUtils.formatMegabytes(row.memoryKb() / 1024.0));
        String status = share >= SHARE_CRITICAL ? STATUS_OFFLINE : share >= SHARE_WARNING ? STATUS_WARNING : null;
        return new PerformanceDTO.Process(
                row.sessionId(),
                "%s · SPID %d".formatted(PerformanceUtils.programLabel(row.programName()), row.sessionId()),
                detail,
                PerformanceUtils.formatPercent(share),
                share,
                status);
    }

    private static PerformanceDTO.Query toQuery(QueryRow row) {
        double totalMs = row.totalElapsedMicros() / 1000.0;
        double averageMs = row.executions() > 0 ? totalMs / row.executions() : 0;
        double cpu = PerformanceUtils.percent(row.totalWorkerMicros(), row.allWorkerMicros());
        String cpuStatus = cpu >= QUERY_CPU_CRITICAL ? STATUS_OFFLINE : cpu >= QUERY_CPU_WARNING ? STATUS_WARNING : null;
        return new PerformanceDTO.Query(
                row.hash(),
                PerformanceUtils.collapse(row.statement(), STATEMENT_PREVIEW_LENGTH),
                PerformanceUtils.collapse(row.statement(), STATEMENT_FULL_LENGTH),
                row.executions(),
                PerformanceUtils.formatMilliseconds(averageMs),
                PerformanceUtils.round(averageMs),
                averageStatus(averageMs),
                PerformanceUtils.formatTotal(totalMs),
                PerformanceUtils.round(totalMs),
                cpu,
                cpuStatus);
    }

    private static PerformanceDTO.Session toSession(RequestRow row) {
        long seconds = Math.max(0, row.elapsedSeconds());
        String status = seconds >= SESSION_CRITICAL_SECONDS
                ? STATUS_OFFLINE
                : seconds >= SESSION_WARNING_SECONDS ? STATUS_WARNING : STATUS_ONLINE;
        return new PerformanceDTO.Session(
                row.sessionId(),
                PerformanceUtils.programLabel(row.programName()),
                row.loginName(),
                row.hostName(),
                row.databaseName(),
                row.command() == null ? null : row.command().trim(),
                PerformanceUtils.formatClock(seconds),
                seconds,
                status);
    }

    private PerformanceDTO.Blocking toBlocking(List<WaitRow> waits) {
        Map<Integer, WaitRow> bySession = waits.stream().collect(Collectors.toMap(
                WaitRow::sessionId,
                Function.identity(),
                (first, second) -> first.waitMs() >= second.waitMs() ? first : second,
                LinkedHashMap::new));
        Map<Integer, List<WaitRow>> byRoot = bySession.values().stream().collect(Collectors.groupingBy(
                row -> rootOf(row, bySession),
                LinkedHashMap::new,
                Collectors.toList()));
        List<PerformanceDTO.Chain> chains = byRoot.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Integer, List<WaitRow>> entry) -> entry.getValue().size())
                        .thenComparingLong(entry -> maxWait(entry.getValue()))
                        .reversed())
                .limit(CHAIN_LIMIT)
                .map(entry -> toChain(entry.getKey(), entry.getValue()))
                .toList();
        return new PerformanceDTO.Blocking(bySession.size(), byRoot.size(), chains);
    }

    private static int rootOf(WaitRow row, Map<Integer, WaitRow> bySession) {
        Set<Integer> visited = new HashSet<>();
        int current = row.blockingSessionId();
        while (bySession.containsKey(current) && visited.add(current)) {
            current = bySession.get(current).blockingSessionId();
        }
        return current;
    }

    private static long maxWait(List<WaitRow> waiters) {
        return waiters.stream().mapToLong(WaitRow::waitMs).max().orElse(0);
    }

    private PerformanceDTO.Chain toChain(int rootId, List<WaitRow> waiters) {
        List<WaitRow> ordered = waiters.stream()
                .sorted(Comparator.comparingLong(WaitRow::waitMs).reversed())
                .toList();
        WaitRow longest = ordered.getFirst();
        Optional<SessionDetailRow> detail = findSessionDetail(rootId);
        LockTarget target = resolveLock(longest);
        Long transactionSeconds = detail.map(SessionDetailRow::transactionSeconds).orElse(null);

        PerformanceDTO.Blocker blocker = detail
                .map(row -> new PerformanceDTO.Blocker(
                        rootId,
                        row.loginName(),
                        PerformanceUtils.programLabel(row.programName()),
                        row.hostName(),
                        row.status()))
                .orElse(new PerformanceDTO.Blocker(rootId, null, null, null, null));
        List<PerformanceDTO.Waiter> list = ordered.stream()
                .map(row -> new PerformanceDTO.Waiter(
                        row.sessionId(),
                        row.loginName(),
                        PerformanceUtils.formatWait(row.waitMs()),
                        row.waitType()))
                .toList();
        String transactionStatus = transactionSeconds != null && transactionSeconds >= TRANSACTION_WARNING_SECONDS
                ? STATUS_WARNING
                : null;

        return new PerformanceDTO.Chain(
                blocker,
                list,
                target.object(),
                target.resource(),
                PerformanceUtils.waitDescription(longest.waitType()),
                PerformanceUtils.formatWait(longest.waitMs()),
                transactionSeconds == null ? "Sin transacción abierta" : PerformanceUtils.formatAge(transactionSeconds),
                transactionStatus,
                detail.map(SessionDetailRow::lastStatement)
                        .map(String::strip)
                        .map(text -> text.length() > STATEMENT_FULL_LENGTH ? text.substring(0, STATEMENT_FULL_LENGTH) + "…" : text)
                        .orElse(null));
    }

    private Optional<SessionDetailRow> findSessionDetail(int sessionId) {
        try {
            return performanceRepository.findSessionDetail(sessionId);
        } catch (DataAccessException e) {
            log.debug("No se pudo obtener el detalle de la sesión {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    private LockTarget resolveLock(WaitRow waiter) {
        String fallback = StringUtils.hasText(waiter.waitResource()) ? waiter.waitResource().trim() : null;
        try {
            return performanceRepository.findWaitingLock(waiter.sessionId())
                    .map(lock -> describeLock(lock).orElse(new LockTarget(lock.databaseName(), fallback)))
                    .orElse(new LockTarget(null, fallback));
        } catch (DataAccessException e) {
            log.debug("No se pudo resolver el recurso bloqueado de la sesión {}: {}", waiter.sessionId(), e.getMessage());
            return new LockTarget(null, fallback);
        }
    }

    private Optional<LockTarget> describeLock(LockRow lock) {
        Optional<LockObjectRow> object = Optional.empty();
        if (OBJECT_RESOURCE.equals(lock.resourceType())) {
            object = performanceRepository.findObject(lock.databaseId(), (int) lock.entityId());
        } else if (HOBT_RESOURCES.contains(lock.resourceType()) && StringUtils.hasText(lock.databaseName())) {
            object = performanceRepository.findHobtObject(lock.databaseName(), lock.entityId());
        }
        return object
                .filter(row -> row.objectName() != null)
                .map(row -> {
                    String qualified = row.schemaName() + "." + row.objectName();
                    String index = row.indexName() == null ? "" : " (%s)".formatted(row.indexName());
                    String database = lock.databaseName();
                    return new LockTarget(
                            database == null ? qualified : database + "." + qualified,
                            "%s: %s%s".formatted(lock.resourceType(), qualified, index));
                });
    }

    private static Set<String> resolveSections(List<String> sections) {
        if (sections == null || sections.isEmpty()) {
            return DEFAULT_SECTIONS;
        }
        Set<String> requested = sections.stream()
                .filter(StringUtils::hasText)
                .map(section -> section.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        requested.stream()
                .filter(section -> !ALL_SECTIONS.contains(section))
                .findFirst()
                .ifPresent(section -> {
                    throw new IllegalArgumentException("Sección no válida: %s.".formatted(section));
                });
        return requested;
    }

    private static ReportUtils.Report toReport(PerformanceDTO data, Set<String> sections) {
        List<String> details = List.of(
                "Instancia: %s · Servidor: %s".formatted(data.instance().name(), data.instance().serverName()),
                "Rango: %s · Generado: %s".formatted(data.range().label(), LocalDateTime.now().format(REPORT_FORMAT))
        );
        PerformanceDTO.Kpis kpis = data.kpis();
        List<ReportUtils.Table> tables = new ArrayList<>();
        tables.add(new ReportUtils.Table(
                "Resumen",
                List.of("Indicador", "Valor", "Detalle"),
                List.of(2, 1, 3),
                List.of(
                        kpiRow("CPU de la instancia", kpis.cpu()),
                        kpiRow("Sesiones activas", kpis.activeSessions()),
                        kpiRow("Sesiones bloqueadas", kpis.blockedSessions()),
                        kpiRow("Tiempo prom. ejecución", kpis.averageDuration())
                )));
        if (sections.contains(SECTION_QUERIES)) {
            tables.add(new ReportUtils.Table(
                    "Consultas SQL con mayor consumo",
                    List.of("query_hash", "Sentencia", "Ejecuciones", "Prom.", "Total", "% CPU"),
                    List.of(4, 15, 3, 2, 2, 2),
                    data.queries().stream()
                            .map(query -> Arrays.<Object>asList(
                                    query.hash(),
                                    query.fullStatement(),
                                    query.executions(),
                                    query.average(),
                                    query.total(),
                                    query.cpuPercent()))
                            .toList()));
        }
        if (sections.contains(SECTION_SESSIONS)) {
            tables.add(new ReportUtils.Table(
                    "Sesiones activas",
                    List.of("SPID", "Programa", "Usuario", "Host", "Base de datos", "Operación", "Duración"),
                    List.of(2, 5, 3, 3, 4, 3, 2),
                    data.sessions().items().stream()
                            .map(session -> Arrays.<Object>asList(
                                    session.sessionId(),
                                    session.program(),
                                    session.login(),
                                    session.host(),
                                    session.database(),
                                    session.operation(),
                                    session.duration()))
                            .toList()));
        }
        if (sections.contains(SECTION_BLOCKING)) {
            tables.add(new ReportUtils.Table(
                    "Sesiones bloqueadas",
                    List.of("Bloqueador", "Sesión bloqueada", "Usuario", "Espera", "Tipo de espera", "Objeto", "Recurso"),
                    List.of(2, 3, 3, 2, 3, 5, 5),
                    data.blocking().chains().stream()
                            .flatMap(chain -> chain.waiters().stream()
                                    .map(waiter -> Arrays.<Object>asList(
                                            chain.blocker().sessionId(),
                                            waiter.sessionId(),
                                            waiter.login(),
                                            waiter.waitTime(),
                                            waiter.waitType(),
                                            chain.object(),
                                            chain.resource())))
                            .toList()));
            tables.add(new ReportUtils.Table(
                    "Bloqueadores raíz",
                    List.of("SPID", "Usuario", "Programa", "Transacción abierta", "Última sentencia"),
                    List.of(2, 3, 3, 3, 11),
                    data.blocking().chains().stream()
                            .map(chain -> Arrays.<Object>asList(
                                    chain.blocker().sessionId(),
                                    chain.blocker().login(),
                                    chain.blocker().program(),
                                    chain.transactionAge(),
                                    PerformanceUtils.collapse(chain.lastStatement(), STATEMENT_FULL_LENGTH)))
                            .toList()));
        }
        ReportUtils.Chart chart = sections.contains(SECTION_CHART) ? toReportChart(data.chart()) : null;
        return new ReportUtils.Report("Reporte de rendimiento", details, tables, chart);
    }

    private static List<Object> kpiRow(String label, PerformanceDTO.Kpi kpi) {
        return Arrays.asList(label, kpi.value(), kpi.detail());
    }

    private static ReportUtils.Chart toReportChart(PerformanceDTO.Chart chart) {
        return new ReportUtils.Chart(
                "CPU y memoria",
                chart.points().stream().map(PerformanceDTO.ChartPoint::time).toList(),
                List.of(
                        new ReportUtils.Series("CPU", CPU_COLOR,
                                chart.points().stream().map(PerformanceDTO.ChartPoint::cpuPercent).toList()),
                        new ReportUtils.Series("Memoria", MEMORY_COLOR,
                                chart.points().stream().map(PerformanceDTO.ChartPoint::memoryPercent).toList())
                ));
    }
}