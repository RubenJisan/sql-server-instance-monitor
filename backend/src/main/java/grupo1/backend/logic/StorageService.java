package grupo1.backend.logic;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import grupo1.backend.data.InstanceRepository;
import grupo1.backend.data.StorageRepository;
import grupo1.backend.data.StorageRepository.FileRow;
import grupo1.backend.data.StorageRepository.GrowthRow;
import grupo1.backend.data.StorageRepository.LogUsageRow;
import grupo1.backend.data.StorageRepository.ObjectRow;
import grupo1.backend.logic.dto.StorageDTO;
import grupo1.backend.logic.utils.InstanceUtils;
import grupo1.backend.logic.utils.StorageUtils;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StorageService {

    private static final String DEFAULT_INSTANCE_NAME = "MSSQLSERVER";
    private static final String STATUS_ONLINE = "online";
    private static final String STATUS_WARNING = "warning";
    private static final String STATUS_OFFLINE = "offline";
    private static final String DATA_FILE_TYPE = "ROWS";
    private static final String GROWTH_SOURCE = "msdb.dbo.backupset · respaldos FULL";
    private static final String NO_HISTORY_LABEL = "Sin historial de respaldos FULL";
    private static final String NO_GROWTH_LABEL = "Sin crecimiento en el período";
    private static final int CRITICAL_FREE_PERCENT = 10;
    private static final int LOW_FREE_PERCENT = 25;
    private static final double CRITICAL_RUNWAY_MONTHS = 3.0;
    private static final double LOW_RUNWAY_MONTHS = 6.0;

    private record GrowthStats(Double monthlyMb, Double trendPercent, Double runwayMonths) {
    }

    private final StorageRepository storageRepository;
    private final InstanceRepository instanceRepository;

    public StorageDTO overview(String requestedDatabase) {
        InstanceRepository.InstanceRow instance = instanceRepository.findInstance();
        List<String> databases = storageRepository.findDatabases();
        String database = resolveDatabase(requestedDatabase, databases);
        List<String> warnings = new ArrayList<>();
        List<FileRow> files = storageRepository.findFiles(database);
        List<LogUsageRow> logUsage = attempt("el uso del log", () -> storageRepository.findLogUsage(database), warnings);
        List<ObjectRow> objects = attempt("los objetos de mayor tamaño", () -> storageRepository.findObjects(database), warnings);
        List<GrowthRow> history = attempt("el historial de crecimiento", () -> storageRepository.findGrowth(database), warnings);

        double dataUsedMb = files.stream()
                .filter(file -> DATA_FILE_TYPE.equals(file.typeDesc()))
                .map(FileRow::usedMb)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        double logUsedMb = logUsage.stream()
                .findFirst()
                .map(row -> StorageUtils.bytesToMegabytes(row.usedBytes()))
                .orElse(0.0);
        double allocatedMb = files.stream().mapToDouble(FileRow::sizeMb).sum();

        return new StorageDTO(
                new StorageDTO.Instance(resolveInstanceName(instance), instance.machineName()),
                database,
                databases,
                toSummary(dataUsedMb + logUsedMb, allocatedMb, history),
                toFilegroups(files),
                files.stream().map(StorageService::toDataFile).toList(),
                objects.stream().map(row -> toObject(database, row)).toList(),
                toGrowth(history),
                warnings
        );
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

    private static String resolveDatabase(String requested, List<String> databases) {
        if (!StringUtils.hasText(requested)) {
            return databases.getFirst();
        }
        return databases.stream()
                .filter(requested::equals)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "La base de datos %s no existe o no está en línea.".formatted(requested)));
    }

    private static String resolveInstanceName(InstanceRepository.InstanceRow row) {
        return StringUtils.hasText(row.instanceName()) ? row.instanceName() : DEFAULT_INSTANCE_NAME;
    }

    private static StorageDTO.Summary toSummary(double usedMb, double allocatedMb, List<GrowthRow> history) {
        double freeMb = Math.max(0, allocatedMb - usedMb);
        Integer freePercent = StorageUtils.percent(freeMb, allocatedMb);
        GrowthStats stats = toGrowthStats(history, freeMb);
        return new StorageDTO.Summary(
                InstanceUtils.formatMegabytes(usedMb),
                stats.trendPercent() == null ? null : StorageUtils.trend(stats.trendPercent()),
                InstanceUtils.formatMegabytes(allocatedMb),
                InstanceUtils.formatMegabytes(freeMb),
                freePercent,
                resolveFreeStatus(freePercent),
                stats.monthlyMb() == null ? null : StorageUtils.signed(stats.monthlyMb()),
                resolveRunwayLabel(stats),
                resolveGrowthStatus(stats)
        );
    }

    private static GrowthStats toGrowthStats(List<GrowthRow> history, double freeMb) {
        if (history.size() < 2) {
            return new GrowthStats(null, null, null);
        }
        GrowthRow first = history.getFirst();
        GrowthRow last = history.getLast();
        GrowthRow previous = history.get(history.size() - 2);
        long months = Math.max(1, ChronoUnit.MONTHS.between(first.monthStart(), last.monthStart()));
        double monthlyMb = (last.sizeMb() - first.sizeMb()) / months;
        Double trend = previous.sizeMb() > 0
                ? 100.0 * (last.sizeMb() - previous.sizeMb()) / previous.sizeMb()
                : null;
        Double runway = monthlyMb > 0 ? freeMb / monthlyMb : null;
        return new GrowthStats(monthlyMb, trend, runway);
    }

    private static String resolveFreeStatus(Integer freePercent) {
        if (freePercent == null) {
            return null;
        }
        if (freePercent < CRITICAL_FREE_PERCENT) {
            return STATUS_OFFLINE;
        }
        return freePercent < LOW_FREE_PERCENT ? STATUS_WARNING : STATUS_ONLINE;
    }

    private static String resolveGrowthStatus(GrowthStats stats) {
        if (stats.monthlyMb() == null) {
            return null;
        }
        if (stats.runwayMonths() == null) {
            return STATUS_ONLINE;
        }
        if (stats.runwayMonths() < CRITICAL_RUNWAY_MONTHS) {
            return STATUS_OFFLINE;
        }
        return stats.runwayMonths() < LOW_RUNWAY_MONTHS ? STATUS_WARNING : STATUS_ONLINE;
    }

    private static String resolveRunwayLabel(GrowthStats stats) {
        if (stats.monthlyMb() == null) {
            return NO_HISTORY_LABEL;
        }
        return stats.runwayMonths() == null ? NO_GROWTH_LABEL : StorageUtils.runway(stats.runwayMonths());
    }

    private static List<StorageDTO.Filegroup> toFilegroups(List<FileRow> files) {
        return files.stream()
                .filter(file -> DATA_FILE_TYPE.equals(file.typeDesc()))
                .filter(file -> file.fileGroup() != null)
                .collect(Collectors.groupingBy(FileRow::fileGroup, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(entry -> toFilegroup(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static StorageDTO.Filegroup toFilegroup(String name, List<FileRow> files) {
        double usedMb = files.stream()
                .map(FileRow::usedMb)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        double totalMb = files.stream().mapToDouble(FileRow::sizeMb).sum();
        return new StorageDTO.Filegroup(
                name,
                InstanceUtils.formatMegabytes(usedMb),
                InstanceUtils.formatMegabytes(totalMb),
                StorageUtils.percent(usedMb, totalMb)
        );
    }

    private static StorageDTO.DataFile toDataFile(FileRow row) {
        String growth = StorageUtils.growthLabel(row.growth(), row.percentGrowth());
        return new StorageDTO.DataFile(
                row.name(),
                row.physicalName(),
                StorageUtils.fileKind(row.typeDesc()),
                row.fileGroup(),
                InstanceUtils.formatMegabytes(row.sizeMb()),
                growth != null,
                growth
        );
    }

    private static StorageDTO.StorageObject toObject(String database, ObjectRow row) {
        String table = StorageUtils.qualifiedName(row.schemaName(), row.objectName());
        boolean index = row.indexId() > 1;
        return new StorageDTO.StorageObject(
                database,
                index ? row.indexName() : table,
                index ? table : null,
                StorageUtils.objectKind(row.indexId(), row.typeDesc()),
                InstanceUtils.formatMegabytes(row.sizeMb())
        );
    }

    private static StorageDTO.Growth toGrowth(List<GrowthRow> history) {
        List<StorageDTO.GrowthPoint> points = history.stream()
                .map(row -> new StorageDTO.GrowthPoint(
                        YearMonth.from(row.monthStart()).toString(),
                        row.sizeMb(),
                        InstanceUtils.formatMegabytes(row.sizeMb())
                ))
                .toList();
        return new StorageDTO.Growth(GROWTH_SOURCE, points);
    }
}
