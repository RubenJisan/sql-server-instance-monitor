package grupo1.backend.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaintenanceRepositoryTests {
    @Test
    void statisticsSnapshotPreservesNullDateAndBindsObjectIdentity() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getInt("stats_id")).thenReturn(3);
        when(rows.getString("name")).thenReturn("empty_stat");
        var snapshot = new MaintenanceRepository(300).statistics(new JdbcTemplate(dataSource), "Example", 12);
        assertEquals(1, snapshot.size());
        assertEquals(3, snapshot.getFirst().id());
        assertNull(snapshot.getFirst().lastUpdated());
        verify(statement).setObject(1, 12);
        verify(rows).close();
        verify(statement).close();
    }

    @Test
    void quotesIdentifiersAndEscapesDynamicSqlLiterals() {
        assertEquals("[odd]]schema].[o'bject]", MaintenanceRepository.qualified("odd]schema", "o'bject"));
        assertEquals("EXEC [da]]ta].sys.sp_executesql N'SELECT N''o''''b'';'",
                MaintenanceRepository.inDatabase("da]ta", "SELECT N'o''b';"));
    }

    @Test
    void refreshHonorsMaintenanceTimeoutDespiteSharedQueryTimeoutAndBindsName() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(30);
        new MaintenanceRepository(180).refresh(jdbc, "Example", new MaintenanceRepository.ModuleRow(
                1, "dbo", "x'; DROP TABLE users;--", "VIEW", false, false, false));
        verify(connection).prepareStatement("EXEC [Example].sys.sp_executesql N'EXEC sys.sp_refreshsqlmodule @name=@module;', N'@module nvarchar(517)', @module=?");
        verify(statement).setNString(1, "[dbo].[x'; DROP TABLE users;--]");
        var order = inOrder(statement);
        order.verify(statement).setQueryTimeout(30);
        order.verify(statement).setQueryTimeout(180);
        order.verify(statement).execute();
        verify(statement).close();
        verify(connection).close();
        assertEquals(30, jdbc.getQueryTimeout());
    }

    @Test
    void closesStatementAndConnectionAndTranslatesSqlFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenThrow(new java.sql.SQLException("Permission denied", "42000", 229));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThrows(DataAccessException.class, () -> new MaintenanceRepository(300).refresh(jdbc, "Example",
                new MaintenanceRepository.ModuleRow(1, "dbo", "sample", "VIEW", false, false, false)));
        verify(statement).close();
        verify(connection).close();
    }
}
