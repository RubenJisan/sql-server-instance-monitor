package grupo1.backend.logic;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Connection(
        String id,
        @NotBlank @Size(max = 60) String name,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]+") String host,
        @Pattern(regexp = "[A-Za-z0-9_$#@-]*") String instanceName,
        @Min(1) @Max(65535) Integer port,
        @NotBlank @Pattern(regexp = "sqlServer|windows") String authentication,
        String username,
        String password,
        boolean encrypt,
        boolean trustServerCertificate,
        @Min(5) int refreshSeconds
) {

    public boolean windowsAuthentication() {
        return "windows".equals(authentication);
    }

    public Connection withId(String newId) {
        return new Connection(newId, name, host, instanceName, port, authentication,
                username, password, encrypt, trustServerCertificate, refreshSeconds);
    }

    public Connection withPassword(String newPassword) {
        return new Connection(id, name, host, instanceName, port, authentication,
                username, newPassword, encrypt, trustServerCertificate, refreshSeconds);
    }

    public Connection withoutPassword() {
        return withPassword(null);
    }
}
