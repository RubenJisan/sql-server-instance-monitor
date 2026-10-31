package grupo1.backend.logic.utils;

import grupo1.backend.logic.Connection;
import grupo1.backend.logic.ConnectionFailedException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.util.StringUtils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class ConnectionUtils {

    private static final int CONNECTION_TIMEOUT_MILLIS = 5000;
    private static final int VALIDATION_TIMEOUT_MILLIS = 3000;

    private ConnectionUtils() {
    }

    public static String buildJdbcUrl(Connection profile) {
        boolean hasPort = profile.port() != null;
        String address = hasPort ? profile.host() + ":" + profile.port() : profile.host();
        String instance = !hasPort && StringUtils.hasText(profile.instanceName())
                ? ";instanceName=" + profile.instanceName()
                : "";
        String integrated = profile.windowsAuthentication() ? ";integratedSecurity=true" : "";

        return "jdbc:sqlserver://%s%s;databaseName=master;encrypt=false;trustServerCertificate=true;loginTimeout=5%s"
                .formatted(address, instance, integrated);
    }

    public static HikariDataSource createDataSource(Connection profile, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("dbwatch-pool");
        config.setJdbcUrl(buildJdbcUrl(profile));
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        config.setValidationTimeout(VALIDATION_TIMEOUT_MILLIS);
        if (!profile.windowsAuthentication()) {
            config.setUsername(profile.username());
            config.setPassword(profile.password());
        }
        try {
            return new HikariDataSource(config);
        } catch (RuntimeException e) {
            String reason = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
            throw new ConnectionFailedException(reason, e);
        }
    }
}
