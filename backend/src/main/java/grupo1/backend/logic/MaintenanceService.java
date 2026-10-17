package grupo1.backend.logic;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import grupo1.backend.data.ActiveConnection;
import grupo1.backend.data.AuditRepository;
import grupo1.backend.data.MaintenanceRepository;
import grupo1.backend.data.MaintenanceRepository.ModuleRow;
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

    public MaintenanceDTO overview(String database) {
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
            repository.requireVisibility(jdbc, selected);
            List<String> warnings = new ArrayList<>();
            var tables = repository.tables(jdbc, selected);
            var findings = auditRepository.findInvalidObjects(selected);
            List<Module> objects = new ArrayList<>();
            for (ModuleRow module : repository.modules(jdbc, selected)) {
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
                    repository.updateStatistics(jdbc, request.database(), table, Boolean.TRUE.equals(request.fullScan()));
                    return result(true, action, request, object, "Estadísticas actualizadas correctamente.");
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
