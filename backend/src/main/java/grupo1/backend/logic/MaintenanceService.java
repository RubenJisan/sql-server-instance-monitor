package grupo1.backend.logic;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import grupo1.backend.data.ActiveConnection;
import grupo1.backend.data.AuditRepository;
import grupo1.backend.data.MaintenanceRepository;
import grupo1.backend.data.MaintenanceRepository.ModuleRow;
import grupo1.backend.data.MaintenanceRepository.StatisticRow;
import grupo1.backend.logic.dto.MaintenanceDTO;
import grupo1.backend.logic.dto.MaintenanceDTO.Module;
import grupo1.backend.logic.dto.MaintenanceDTO.Request;
import grupo1.backend.logic.dto.MaintenanceDTO.Result;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MaintenanceService {
    private final ActiveConnection activeConnection;
    private final AuditRepository auditRepository;
    private final MaintenanceRepository repository;

    public MaintenanceDTO getOverview(String database) {
        synchronized (activeConnection) {
            Connection profile = activeConnection.profile().orElseThrow(NoActiveConnectionException::new);
            JdbcTemplate jdbc = activeConnection.jdbcTemplate();
            List<String> databases = auditRepository.findUserDatabases();
            if (databases.isEmpty()) {
                return new MaintenanceDTO(profile.id(), profile.name(), databases, null, List.of(), List.of(),
                        List.of("No hay bases de usuario en línea visibles para esta conexión."), Instant.now());
            }
            String selected = database == null || database.isBlank() ? databases.getFirst() : database;
            requireDatabase(databases, selected);
            List<String> warnings = new ArrayList<>();
            try {
                repository.requireVisibility(jdbc, selected);
            } catch (IllegalArgumentException | DataAccessException exception) {
                warnings.add("No se pudo analizar la base " + selected + ": "
                        + NestedExceptionUtils.getMostSpecificCause(exception).getMessage()
                        + " Puede seleccionar otra base o revisar sus permisos y disponibilidad.");
                return new MaintenanceDTO(profile.id(), profile.name(), databases, selected, List.of(), List.of(), warnings, Instant.now());
            }
            var tables = attempt("las estadísticas de " + selected, () -> repository.tables(jdbc, selected), warnings);
            var findings = attempt("las referencias de " + selected, () -> auditRepository.findInvalidObjects(selected), warnings);
            List<Module> objects = new ArrayList<>();
            for (ModuleRow module : attempt("los módulos de " + selected, () -> repository.modules(jdbc, selected), warnings)) {
                String problem = findings.stream()
                        .filter(row -> row.schemaName().equals(module.schema()) && row.objectName().equals(module.name()))
                        .map(row -> "Dependencia sin resolver: " + row.referencedName()).distinct()
                        .reduce((first, next) -> first + "; " + next).orElse(null);
                if (module.encrypted()) {
                    warnings.add(module.schema() + "." + module.name() + ": definición cifrada; no se pudo validar.");
                } else {
                    try {
                        List<String> unresolved = repository.unresolved(jdbc, selected, module.schema(), module.name());
                        if (!unresolved.isEmpty()) problem = "Dependencias sin resolver: " + String.join(", ", unresolved);
                    } catch (DataAccessException exception) {
                        if (isDependencyError(exception)) {
                            problem = reason(exception);
                        } else {
                            warnings.add(module.schema() + "." + module.name() + ": validación incompleta. " + reason(exception));
                        }
                    }
                }
                if (problem != null) {
                    if (!module.repairable()) problem += " Requiere intervención manual (cifrado, firma o SCHEMABINDING).";
                    objects.add(new Module(module.objectId(), module.schema(), module.name(), module.type(),
                            problem, module.repairable()));
                }
            }
            return new MaintenanceDTO(profile.id(), profile.name(), databases, selected, tables, objects, warnings, Instant.now());
        }
    }

    public Result run(String action, Request request) {
        synchronized (activeConnection) {
            if (!Boolean.TRUE.equals(request.confirmed())) throw new IllegalArgumentException("Debe confirmar la operación.");
            Connection profile = activeConnection.profile().orElseThrow(NoActiveConnectionException::new);
            if (!profile.id().equals(request.profileId())) {
                throw new IllegalArgumentException("La conexión activa cambió. Actualice el análisis y confirme de nuevo.");
            }
            JdbcTemplate jdbc = activeConnection.jdbcTemplate();
            requireDatabase(auditRepository.findUserDatabases(), request.database());
            repository.requireVisibility(jdbc, request.database());
            String object = request.schema() + "." + request.name();
            try {
                if ("statistics".equals(action)) {
                    var table = repository.tables(jdbc, request.database()).stream()
                            .filter(row -> row.objectId() == request.objectId() && row.schema().equals(request.schema())
                                    && row.name().equals(request.name())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("La tabla ya no existe o cambió. Actualice el análisis."));
                    if (table.statistics() == 0) throw new IllegalArgumentException("La tabla no tiene estadísticas existentes para actualizar.");
                    List<StatisticRow> before = repository.statistics(jdbc, request.database(), table.objectId());
                    repository.updateStatistics(jdbc, request.database(), table, Boolean.TRUE.equals(request.fullScan()));
                    return verifyStatistics(jdbc, request, table.statistics(), before);
                }
                if (!"refresh".equals(action)) throw new IllegalArgumentException("Acción de mantenimiento no válida.");
                ModuleRow module = repository.modules(jdbc, request.database()).stream()
                        .filter(row -> row.objectId() == request.objectId() && row.schema().equals(request.schema())
                                && row.name().equals(request.name())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("El módulo ya no existe o cambió. Actualice el análisis."));
                if (!module.repairable()) throw new IllegalArgumentException("Este módulo requiere intervención manual: firma, cifrado o SCHEMABINDING.");
                repository.refresh(jdbc, request.database(), module);
                List<String> unresolved = repository.unresolved(jdbc, request.database(), module.schema(), module.name());
                if (!unresolved.isEmpty()) return result(false, action, request, object,
                        "Metadatos actualizados; persisten dependencias sin resolver: " + String.join(", ", unresolved)
                                + ". Corrija las referencias o restaure los objetos faltantes y vuelva a intentar.");
                return result(true, action, request, object, "Metadatos actualizados y dependencias verificadas correctamente.");
            } catch (DataAccessException exception) {
                return result(false, action, request, object, "No se pudo completar o verificar la operación. " + reason(exception)
                        + " Revise permisos y dependencias; si hubo tiempo de espera agotado, verifique el estado antes de reintentar.");
            }
        }
    }

    private static void requireDatabase(List<String> databases, String database) {
        if (!databases.contains(database)) throw new IllegalArgumentException("Seleccione una base de usuario visible y en línea.");
    }

    private static <T> List<T> attempt(String section, Supplier<List<T>> query, List<String> warnings) {
        try {
            return query.get();
        } catch (DataAccessException exception) {
            warnings.add("No se pudo obtener " + section + ": " + reason(exception));
            return List.of();
        }
    }

    private Result verifyStatistics(JdbcTemplate jdbc, Request request, int expectedCount, List<StatisticRow> before) {
        String object = request.schema() + "." + request.name();
        List<StatisticRow> after;
        try {
            after = repository.statistics(jdbc, request.database(), request.objectId());
        } catch (DataAccessException exception) {
            return unverified(request, "El comando de actualización se ejecutó, pero no se pudo consultar el resultado. "
                    + reason(exception) + " Actualice el análisis y revise los permisos antes de reintentar.");
        }
        if (before.size() != expectedCount || after.size() != expectedCount
                || after.stream().anyMatch(current -> before.stream().noneMatch(previous ->
                        previous.id() == current.id() && previous.name().equals(current.name())))) {
            return unverified(request, "El comando se ejecutó, pero cambió la lista de estadísticas o no es completamente visible. "
                    + "Actualice el análisis antes de repetir la operación.");
        }
        long withoutDate = after.stream().filter(row -> row.lastUpdated() == null).count();
        if (withoutDate > 0) {
            return unverified(request, "El comando se ejecutó; " + withoutDate + " de " + expectedCount
                    + " estadísticas siguen sin fecha disponible. SQL Server puede no generar una fecha cuando no hay filas "
                    + "para esa estadística; también puede faltar permiso para consultar sus propiedades. "
                    + "Esto no demuestra un fallo de la actualización. No se agregan datos ni se reintenta automáticamente.");
        }
        boolean allAdvanced = after.stream().allMatch(current -> before.stream().anyMatch(previous ->
                previous.id() == current.id() && previous.name().equals(current.name())
                        && (previous.lastUpdated() == null || current.lastUpdated().isAfter(previous.lastUpdated()))));
        if (!allAdvanced) {
            return unverified(request, "El comando se ejecutó, pero no se observó una fecha posterior en todas las estadísticas. "
                    + "La actualización no se puede confirmar con esta lectura; no se considera un error SQL ni se reintenta. "
                    + "Actualice el análisis y revise las propiedades y los permisos. El número de estadísticas no tiene que aumentar.");
        }
        return result(true, "statistics", request, object, "Actualización verificada: las fechas avanzaron en las "
                + expectedCount + " estadísticas existentes. El número de estadísticas no aumenta por actualizarlas; "
                + "un contador de cambios igual a 0 es normal tras la actualización.");
    }

    private static Result unverified(Request request, String message) {
        return new Result(false, "statistics", request.database(), request.schema() + "." + request.name(),
                message, Instant.now(), "unverified");
    }

    private static Result result(boolean ok, String action, Request request, String object, String message) {
        return new Result(ok, action, request.database(), object, message, Instant.now());
    }

    private static String reason(DataAccessException exception) {
        return NestedExceptionUtils.getMostSpecificCause(exception).getMessage();
    }

    private static boolean isDependencyError(DataAccessException exception) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        if (!(cause instanceof SQLException sqlException)) return false;
        return List.of(207, 208, 2020, 4121, 4413, 5313).contains(sqlException.getErrorCode());
    }
}
