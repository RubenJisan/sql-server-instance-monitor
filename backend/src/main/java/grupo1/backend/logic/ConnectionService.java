package grupo1.backend.logic;

import java.util.List;

import grupo1.backend.data.ActiveConnection;
import grupo1.backend.data.ProfileData;
import grupo1.backend.logic.utils.ConnectionUtils;
import grupo1.backend.logic.dto.ConnectionOverviewDTO;
import grupo1.backend.logic.dto.ConnectionTestDTO;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.zaxxer.hikari.HikariDataSource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionService {

    private static final String TEST_QUERY = """
            SELECT CAST(SERVERPROPERTY('ServerName') AS NVARCHAR(128)) AS serverName,
                   CAST(SERVERPROPERTY('ProductVersion') AS NVARCHAR(50)) AS version
            """;

    private final ProfileData profileStore;
    private final ActiveConnection activeConnection;

    public ConnectionOverviewDTO getOverview() {
        List<Connection> profiles = profileStore.findAll().stream()
                .map(Connection::withoutPassword)
                .toList();
        return new ConnectionOverviewDTO(profiles, activeProfileId());
    }

    public Connection save(Connection profile) {
        validateCredentials(profile);
        Connection saved = profileStore.save(profile);

        if (saved.id().equals(activeProfileId())) {
            activeConnection.activate(saved);
        }
        return saved.withoutPassword();
    }

    public void delete(String id) {
        findOrThrow(id);
        boolean wasActive = id.equals(activeProfileId());
        profileStore.delete(id);
        if (wasActive) {
            activeConnection.deactivate();
        }
    }

    public ConnectionTestDTO test(Connection profile) {
        validateCredentials(profile);
        Connection resolved = withStoredPassword(profile);

        try (HikariDataSource dataSource = ConnectionUtils.createDataSource(resolved, 1)) {
            return new JdbcTemplate(dataSource).queryForObject(
                    TEST_QUERY,
                    (rs, rowNum) -> new ConnectionTestDTO(rs.getString("serverName"), rs.getString("version"))
            );
        }
    }

    public Connection activate(String id) {
        Connection profile = findOrThrow(id);
        activeConnection.activate(profile);
        profileStore.setActiveId(id);
        return profile.withoutPassword();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreActiveProfile() {
        profileStore.findActiveId()
                .flatMap(profileStore::findById)
                .ifPresent(profile -> {
                    try {
                        activeConnection.activate(profile);
                        log.info("Instancia activa restaurada: {}", profile.name());
                    } catch (ConnectionFailedException e) {
                        log.warn("No se pudo restaurar la instancia activa '{}': {}", profile.name(), e.getMessage());
                    }
                });
    }

    private String activeProfileId() {
        return activeConnection.profile().map(Connection::id).orElse(null);
    }

    private Connection findOrThrow(String id) {
        return profileStore.findById(id).orElseThrow(() -> new ProfileNotFoundException(id));
    }

    private Connection withStoredPassword(Connection profile) {
        if (StringUtils.hasText(profile.password()) || !StringUtils.hasText(profile.id())) {
            return profile;
        }
        return profileStore.findById(profile.id())
                .map(stored -> profile.withPassword(stored.password()))
                .orElse(profile);
    }

    private void validateCredentials(Connection profile) {
        boolean missingUser = !profile.windowsAuthentication() && !StringUtils.hasText(profile.username());
        if (missingUser) {
            throw new IllegalArgumentException("El usuario es obligatorio con autenticación SQL Server.");
        }
    }
}
