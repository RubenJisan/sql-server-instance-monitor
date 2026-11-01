package grupo1.backend.logic;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import grupo1.backend.data.ActiveConnection;
import grupo1.backend.data.AuditRepository;
import grupo1.backend.data.MaintenanceRepository;
import grupo1.backend.data.MaintenanceRepository.ModuleRow;
import grupo1.backend.data.MaintenanceRepository.StatisticRow;
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
        when(repository.statistics(jdbc, "Example", 12)).thenReturn(
                List.of(new StatisticRow(1, "stat", LocalDateTime.of(2026, 10, 30, 14, 0))),
                List.of(new StatisticRow(1, "stat", LocalDateTime.of(2026, 11, 1, 14, 0))));
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
        var result = service.run("statistics", request(true, "profile"));
        assertTrue(result.ok());
        assertEquals("verified", result.status());
        verify(repository).updateStatistics(eq(jdbc), eq("Example"), any(Table.class), eq(true));
        var order = inOrder(repository);
        order.verify(repository).requireVisibility(jdbc, "Example");
        order.verify(repository).tables(jdbc, "Example");
        order.verify(repository).statistics(jdbc, "Example", 12);
        order.verify(repository).updateStatistics(eq(jdbc), eq("Example"), any(Table.class), eq(true));
        order.verify(repository).statistics(jdbc, "Example", 12);
    }

    @Test
    void unavailableDatesDoNotProduceFalseSuccessOrClaimThatTableIsEmpty() {
        when(repository.statistics(jdbc, "Example", 12)).thenReturn(List.of(new StatisticRow(1, "stat", null)));
        var result = service.run("statistics", request(true, "profile"));
        assertFalse(result.ok());
        assertEquals("unverified", result.status());
        assertTrue(result.message().contains("sin fecha disponible"));
        assertTrue(result.message().contains("permiso"));
        verify(repository, times(1)).updateStatistics(eq(jdbc), eq("Example"), any(), eq(true));
    }

    @Test
    void unchangedOrOlderDatesAreNotVerified() {
        var original = new StatisticRow(1, "stat", LocalDateTime.of(2026, 10, 30, 14, 0));
        for (int days : List.of(0, -1)) {
            when(repository.statistics(jdbc, "Example", 12)).thenReturn(List.of(original),
                    List.of(new StatisticRow(1, "stat", original.lastUpdated().plusDays(days))));
            var result = service.run("statistics", request(true, "profile"));
            assertFalse(result.ok());
            assertEquals("unverified", result.status());
            assertTrue(result.message().contains("no se observó una fecha posterior"));
        }
    }

    @Test
    void newDateAfterPreviouslyMissingDateCanBeVerified() {
        when(repository.statistics(jdbc, "Example", 12)).thenReturn(List.of(new StatisticRow(1, "stat", null)),
                List.of(new StatisticRow(1, "stat", LocalDateTime.of(2026, 11, 1, 14, 0))));
        assertEquals("verified", service.run("statistics", request(true, "profile")).status());
    }

    @Test
    void partialDateAdvanceDoesNotVerifyWholeTable() {
        var date = LocalDateTime.of(2026, 10, 30, 14, 0);
        when(repository.tables(jdbc, "Example")).thenReturn(List.of(new Table(12, "dbo", "broken", 2, date.toString(), 0L)));
        when(repository.statistics(jdbc, "Example", 12)).thenReturn(
                List.of(new StatisticRow(1, "first", date), new StatisticRow(2, "second", date)),
                List.of(new StatisticRow(1, "first", date.plusDays(1)), new StatisticRow(2, "second", date)));
        assertEquals("unverified", service.run("statistics", request(true, "profile")).status());
    }

    @Test
    void changedOrInvisibleStatisticsCannotBeVerified() {
        for (var after : List.of(List.<StatisticRow>of(), List.of(new StatisticRow(2, "replacement", LocalDateTime.now())))) {
            when(repository.statistics(jdbc, "Example", 12)).thenReturn(
                    List.of(new StatisticRow(1, "stat", LocalDateTime.of(2026, 10, 30, 14, 0))), after);
            var result = service.run("statistics", request(true, "profile"));
            assertEquals("unverified", result.status());
            assertTrue(result.message().contains("lista de estadísticas"));
        }
    }

    @Test
    void postUpdateReadFailureIsUnverifiedAndDoesNotRetryWrite() {
        when(repository.statistics(jdbc, "Example", 12)).thenReturn(List.of(new StatisticRow(1, "stat", null)))
                .thenThrow(new InvalidDataAccessResourceUsageException("Read permission denied"));
        var result = service.run("statistics", request(true, "profile"));
        assertEquals("unverified", result.status());
        assertTrue(result.message().contains("se ejecutó, pero no se pudo consultar"));
        verify(repository, times(1)).updateStatistics(eq(jdbc), eq("Example"), any(), eq(true));
    }

    @Test
    void preUpdateReadFailureDoesNotWrite() {
        doThrow(new InvalidDataAccessResourceUsageException("Read permission denied"))
                .when(repository).statistics(jdbc, "Example", 12);
        assertEquals("failed", service.run("statistics", request(true, "profile")).status());
        verify(repository, never()).updateStatistics(any(), anyString(), any(), anyBoolean());
    }

    @Test
    void commandFailureIsNotMisclassifiedAsUnverified() {
        doThrow(new InvalidDataAccessResourceUsageException("ALTER permission denied"))
                .when(repository).updateStatistics(eq(jdbc), eq("Example"), any(), eq(true));
        assertEquals("failed", service.run("statistics", request(true, "profile")).status());
        verify(repository, times(1)).statistics(jdbc, "Example", 12);
    }

    @Test
    void noExistingStatisticsCannotBeUpdated() {
        when(repository.tables(jdbc, "Example")).thenReturn(List.of(new Table(12, "dbo", "broken", 0, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.run("statistics", request(true, "profile")));
        verify(repository, never()).updateStatistics(any(), anyString(), any(), anyBoolean());
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
        var broken = service.getOverview("Example");
        assertEquals(1, broken.objects().size());
        assertTrue(broken.warnings().isEmpty());
        doThrow(new InvalidDataAccessResourceUsageException("denied", new SQLException("Permission denied", "S0001", 229)))
                .when(repository).unresolved(jdbc, "Example", "dbo", "broken");
        var partial = service.getOverview("Example");
        assertTrue(partial.objects().isEmpty());
        assertEquals(1, partial.warnings().size());
    }

    @Test
    void handlesNoActiveConnectionAndEmptyDatabaseList() {
        when(audit.findUserDatabases()).thenReturn(List.of());
        assertNull(service.getOverview(null).database());
        when(active.profile()).thenReturn(Optional.empty());
        assertThrows(NoActiveConnectionException.class, () -> service.getOverview(null));
    }
}
