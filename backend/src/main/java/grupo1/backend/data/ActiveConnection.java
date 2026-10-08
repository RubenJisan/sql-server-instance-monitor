package grupo1.backend.data;

import java.util.Optional;

import grupo1.backend.logic.utils.ConnectionUtils;
import grupo1.backend.logic.Connection;
import grupo1.backend.logic.NoActiveConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

@Component
public class ActiveConnection {

    private static final int POOL_SIZE = 3;
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private volatile HikariDataSource dataSource;
    private volatile JdbcTemplate jdbcTemplate;
    private volatile Connection profile;

    public synchronized void activate(Connection newProfile) {
        HikariDataSource created = ConnectionUtils.createDataSource(newProfile, POOL_SIZE);
        JdbcTemplate template = new JdbcTemplate(created);
        template.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        HikariDataSource previous = dataSource;
        dataSource = created;
        jdbcTemplate = template;
        profile = newProfile;
        Optional.ofNullable(previous).ifPresent(HikariDataSource::close);
    }

    @PreDestroy
    public synchronized void deactivate() {
        Optional.ofNullable(dataSource).ifPresent(HikariDataSource::close);
        dataSource = null;
        jdbcTemplate = null;
        profile = null;
    }

    public JdbcTemplate jdbcTemplate() {
        return Optional.ofNullable(jdbcTemplate).orElseThrow(NoActiveConnectionException::new);
    }

    public Optional<Connection> profile() {
        return Optional.ofNullable(profile);
    }
}
