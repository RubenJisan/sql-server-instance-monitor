package grupo1.backend.logic;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import grupo1.backend.data.ActiveConnection;
import grupo1.backend.data.AuditRepository;
import grupo1.backend.data.MaintenanceRepository;
import grupo1.backend.data.MaintenanceRepository.ModuleRow;
import grupo1.backend.logic.dto.MaintenanceDTO.Request;
import grupo1.backend.logic.dto.MaintenanceDTO.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceServiceTests {
    private final ActiveConnection active = mock(ActiveConnection.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final MaintenanceRepository repository = mock(MaintenanceRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MaintenanceService service = new MaintenanceService(active, audit, repository);
    private final ModuleRow module = new ModuleRow(12, "dbo", "broken", "VIEW", false, false, false);

    @BeforeEach
    void setup() {
        when(active.profile()).thenReturn(Optional.of(new Connection("profile", "Local", "localhost", "", 1433,
                "sqlServer", "user", null, true, true, 10)));
        when(active.jdbcTemplate()).thenReturn(jdbc);
        when(audit.findUserDatabases()).thenReturn(List.of("Example"));
        when(repository.modules(jdbc, "Example")).thenReturn(List.of(module));
        when(repository.tables(jdbc, "Example")).thenReturn(List.of(new Table(12, "dbo", "broken", 1, null, 2L)));
    }

    private Request request(boolean confirmed, String profile) {
        return new Request(profile, "Example", 12, "dbo", "broken", confirmed, true);
    }

    @Test
    void requiresExplicitConfirmation() {
        assertThrows(IllegalArgumentException.class, () -> service.run("statistics", request(false, "profile")));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsChangedConnectionBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class, () -> service.run("refresh", request(true, "old-profile")));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsDatabasesOutsideVisibleUserDatabases() {
        when(audit.findUserDatabases()).thenReturn(List.of("Other"));
        assertThrows(IllegalArgumentException.class, () -> service.run("statistics", request(true, "profile")));
        verifyNoInteractions(repository);
    }

    @Test
    void updatesStatisticsWithRequestedSampling() {
        assertTrue(service.run("statistics", request(true, "profile")).ok());
        verify(repository).updateStatistics(eq(jdbc), eq("Example"), any(Table.class), eq(true));
    }

    @Test
    void rejectsStaleObjectIdentity() {
        Request stale = new Request("profile", "Example", 99, "dbo", "broken", true, false);
        assertThrows(IllegalArgumentException.class, () -> service.run("statistics", stale));
        verify(repository, never()).updateStatistics(any(), anyString(), any(), anyBoolean());
    }

    @Test
    void doesNotReportSuccessWhenRefreshLeavesMissingDependencies() {
        when(repository.unresolved(jdbc, "Example", "dbo", "broken")).thenReturn(List.of("dbo.missing"));
        var result = service.run("refresh", request(true, "profile"));
        assertFalse(result.ok());
        assertTrue(result.message().contains("dbo.missing"));
        verify(repository).refresh(jdbc, "Example", module);
    }

    @Test
    void verifiesDependenciesAfterSuccessfulRefresh() {
        assertTrue(service.run("refresh", request(true, "profile")).ok());
        var order = inOrder(repository);
        order.verify(repository).requireVisibility(jdbc, "Example");
        order.verify(repository).modules(jdbc, "Example");
        order.verify(repository).refresh(jdbc, "Example", module);
        order.verify(repository).unresolved(jdbc, "Example", "dbo", "broken");
    }

    @Test
    void protectsSignedModules() {
        when(repository.modules(jdbc, "Example")).thenReturn(List.of(new ModuleRow(12, "dbo", "broken", "VIEW", false, false, true)));
        assertThrows(IllegalArgumentException.class, () -> service.run("refresh", request(true, "profile")));
        verify(repository, never()).refresh(any(), anyString(), any());
    }

    @Test
    void reportsDatabaseFailureAndDoesNotClaimSuccess() {
        doThrow(new InvalidDataAccessResourceUsageException("failure", new SQLException("Permission denied", "S0001", 229)))
                .when(repository).refresh(jdbc, "Example", module);
        var result = service.run("refresh", request(true, "profile"));
        assertFalse(result.ok());
        assertTrue(result.message().contains("Permission denied"));
    }

    @Test
    void scanDistinguishesDependencyErrorsFromPermissionWarnings() {
        when(repository.unresolved(jdbc, "Example", "dbo", "broken"))
                .thenThrow(new InvalidDataAccessResourceUsageException("binding", new SQLException("Invalid column", "S0001", 207)));
        var broken = service.overview("Example");
        assertEquals(1, broken.objects().size());
        assertTrue(broken.warnings().isEmpty());
        doThrow(new InvalidDataAccessResourceUsageException("denied", new SQLException("Permission denied", "S0001", 229)))
                .when(repository).unresolved(jdbc, "Example", "dbo", "broken");
        var partial = service.overview("Example");
        assertTrue(partial.objects().isEmpty());
        assertEquals(1, partial.warnings().size());
    }

    @Test
    void handlesNoActiveConnectionAndEmptyDatabaseList() {
        when(audit.findUserDatabases()).thenReturn(List.of());
        assertNull(service.overview(null).database());
        when(active.profile()).thenReturn(Optional.empty());
        assertThrows(NoActiveConnectionException.class, () -> service.overview(null));
    }
}
