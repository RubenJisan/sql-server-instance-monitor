package grupo1.backend.logic;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import grupo1.backend.data.AuditRepository;
import grupo1.backend.logic.dto.AuditDTO;
import grupo1.backend.logic.utils.AuditUtils;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import grupo1.backend.logic.dto.AuditDTO.InvalidObject;
import grupo1.backend.logic.dto.AuditDTO.Metrics;
import grupo1.backend.logic.dto.AuditDTO.Privilege;
import grupo1.backend.logic.dto.AuditDTO.Role;
import grupo1.backend.logic.dto.AuditDTO.Scan;
import grupo1.backend.logic.dto.AuditDTO.User;
import grupo1.backend.data.AuditRepository.LoginRow;
import grupo1.backend.data.AuditRepository.MembershipRow;
import grupo1.backend.data.AuditRepository.PermissionRow;
import grupo1.backend.data.AuditRepository.RoleRow;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int EXPIRING_DAYS_AHEAD = 30;
    private static final String ROLE_PUBLIC = "public";
    private static final String ROLE_SYSADMIN = "sysadmin";
    private static final Set<String> CRITICAL_ROLES = Set.of("sysadmin", "securityadmin", "serveradmin", "setupadmin");
    private static final String STATUS_ONLINE = "online";
    private static final String STATUS_WARNING = "warning";
    private static final String STATUS_OFFLINE = "offline";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private record Status(String code, String label) {
    }

    private record InvalidScan(List<String> databases, List<InvalidObject> objects, List<String> warnings) {
    }

    private final AuditRepository auditRepository;

    public AuditDTO getOverview() {
        List<LoginRow> logins = auditRepository.findLogins();
        List<RoleRow> roleRows = auditRepository.findRoles();
        List<MembershipRow> memberships = auditRepository.findMemberships();
        List<PermissionRow> permissions = auditRepository.findPermissions();
        InvalidScan scan = scanInvalidObjects();
        Map<String, List<String>> rolesByMember = memberships.stream().collect(
                Collectors.groupingBy(MembershipRow::memberName,
                        Collectors.mapping(MembershipRow::roleName, Collectors.toList())));
        Map<String, List<String>> membersByRole = memberships.stream().collect(
                Collectors.groupingBy(MembershipRow::roleName,
                        Collectors.mapping(MembershipRow::memberName, Collectors.toList())));
        Map<String, List<PermissionRow>> permissionsByGrantee = permissions.stream().collect(
                Collectors.groupingBy(PermissionRow::grantee));
        List<User> users = logins.stream()
                .map(login -> toUser(login, rolesByMember.getOrDefault(login.name(), List.of())))
                .toList();
        List<Role> roles = roleRows.stream()
                .map(row -> toRole(row, membersByRole, permissionsByGrantee, logins.size()))
                .toList();
        List<Privilege> privileges = toPrivileges(logins, permissions, rolesByMember, permissionsByGrantee);
        return new AuditDTO(
                toMetrics(logins, roles, membersByRole, scan.objects()),
                users,
                roles,
                privileges,
                scan.objects(),
                new Scan(scan.databases(), scan.warnings())
        );
    }

    private InvalidScan scanInvalidObjects() {
        List<String> databases = auditRepository.findUserDatabases();
        List<InvalidObject> objects = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String database : databases) {
            try {
                auditRepository.findInvalidObjects(database).stream()
                        .map(row -> new InvalidObject(
                                database,
                                row.schemaName(),
                                row.objectName(),
                                AuditUtils.objectType(row.typeDesc()),
                                "Referencia a un objeto inexistente: " + row.referencedName()
                        ))
                        .forEach(objects::add);
            } catch (DataAccessException e) {
                String reason = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
                warnings.add("No se pudo analizar la base %s: %s".formatted(database, reason));
            }
        }
        return new InvalidScan(databases, objects, warnings);
    }

    private static Metrics toMetrics(
            List<LoginRow> logins,
            List<Role> roles,
            Map<String, List<String>> membersByRole,
            List<InvalidObject> invalidObjects
    ) {
        int enabled = (int) logins.stream().filter(login -> !login.disabled()).count();
        int sysadminMembers = (int) membersByRole.getOrDefault(ROLE_SYSADMIN, List.of()).stream().distinct().count();
        int expiring = (int) logins.stream()
                .filter(login -> !login.disabled())
                .filter(login -> login.daysUntilExpiration() != null)
                .filter(login -> login.daysUntilExpiration() <= EXPIRING_DAYS_AHEAD)
                .count();
        int invalidSchemas = (int) invalidObjects.stream()
                .map(object -> object.database() + "." + object.schema())
                .distinct()
                .count();

        return new Metrics(
                logins.size(),
                enabled,
                logins.size() - enabled,
                roles.size(),
                sysadminMembers,
                expiring,
                EXPIRING_DAYS_AHEAD,
                invalidObjects.size(),
                invalidSchemas
        );
    }

    private static User toUser(LoginRow login, List<String> memberOf) {
        Status status = resolveUserStatus(login);
        List<String> roles = memberOf.stream().sorted().toList();
        return new User(
                login.name(),
                AuditUtils.loginType(login.type()),
                AuditUtils.principalRole(roles),
                roles,
                status.code(),
                status.label(),
                resolveExpiration(login),
                login.createDate().format(DATE_FORMAT),
                login.defaultDatabase()
        );
    }

    private static Status resolveUserStatus(LoginRow login) {
        boolean sqlLogin = "S".equals(login.type());
        if (login.disabled()) {
            return new Status(STATUS_OFFLINE, "DESHABILITADO");
        }
        if (login.builtInAdmin()) {
            return new Status(STATUS_WARNING, "CUENTA SA HABILITADA");
        }
        if (Boolean.TRUE.equals(login.locked())) {
            return new Status(STATUS_WARNING, "BLOQUEADO");
        }
        if (login.daysUntilExpiration() != null && login.daysUntilExpiration() < 0) {
            return new Status(STATUS_WARNING, "CONTRASEÑA EXPIRADA");
        }
        if (sqlLogin && Boolean.FALSE.equals(login.policyChecked())) {
            return new Status(STATUS_WARNING, "SIN POLÍTICA DE CONTRASEÑA");
        }
        return new Status(STATUS_ONLINE, "HABILITADO");
    }

    private static String resolveExpiration(LoginRow login) {
        if (!"S".equals(login.type())) {
            return null;
        }
        if (login.expiresOn() != null) {
            return login.expiresOn().toString();
        }
        return Boolean.FALSE.equals(login.expirationChecked()) ? "Sin vencimiento" : null;
    }

    private static Role toRole(
            RoleRow row,
            Map<String, List<String>> membersByRole,
            Map<String, List<PermissionRow>> permissionsByGrantee,
            int loginCount
    ) {
        List<String> members = membersByRole.getOrDefault(row.name(), List.of()).stream().sorted().toList();
        List<String> permissionNames = permissionsByGrantee.getOrDefault(row.name(), List.of()).stream()
                .filter(permission -> !"DENY".equals(permission.state()))
                .map(PermissionRow::permission)
                .distinct()
                .sorted()
                .toList();
        boolean critical = CRITICAL_ROLES.contains(row.name()) || permissionNames.contains("CONTROL SERVER");
        int userCount = ROLE_PUBLIC.equals(row.name()) ? loginCount : members.size();

        return new Role(
                row.name(),
                row.fixed(),
                permissionNames.isEmpty() ? null : String.join(", ", permissionNames),
                userCount,
                members,
                critical
        );
    }

    private static List<Privilege> toPrivileges(
            List<LoginRow> logins,
            List<PermissionRow> permissions,
            Map<String, List<String>> rolesByMember,
            Map<String, List<PermissionRow>> permissionsByGrantee
    ) {
        Stream<Privilege> assigned = permissions.stream()
                .map(permission -> toPrivilege(
                        permission.grantee(),
                        permission,
                        "R".equals(permission.granteeType()) ? "Definición del rol" : "Directo"
                ));

        Stream<Privilege> inherited = logins.stream()
                .flatMap(login -> rolesByMember.getOrDefault(login.name(), List.of()).stream()
                        .filter(role -> !ROLE_PUBLIC.equals(role))
                        .flatMap(role -> permissionsByGrantee.getOrDefault(role, List.of()).stream()
                                .map(permission -> toPrivilege(login.name(), permission, "Rol: " + role))));

        return Stream.concat(assigned, inherited)
                .sorted(Comparator.comparing(Privilege::grantee, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Privilege::privilege)
                        .thenComparing(Privilege::source))
                .toList();
    }

    private static Privilege toPrivilege(String grantee, PermissionRow permission, String source) {
        Status status = switch (permission.state()) {
            case "DENY" -> new Status(STATUS_OFFLINE, "DENEGADO");
            case "GRANT_WITH_GRANT_OPTION" -> new Status(STATUS_WARNING, "CONCEDIDO CON GRANT");
            default -> new Status(STATUS_ONLINE, "CONCEDIDO");
        };

        return new Privilege(
                grantee,
                permission.permission(),
                permission.object(),
                permission.grantor(),
                status.code(),
                status.label(),
                source
        );
    }
}
