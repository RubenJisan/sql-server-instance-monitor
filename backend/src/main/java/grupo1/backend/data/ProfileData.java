package grupo1.backend.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import grupo1.backend.logic.Connection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ProfileData {

    record StoredState(List<Connection> profiles, String activeId) {
        StoredState {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }
    }

    private final JsonMapper jsonMapper;
    private final Path file;

    public ProfileData(JsonMapper jsonMapper, @Value("${app.configDir}") String configDir) {
        this.jsonMapper = jsonMapper;
        this.file = Path.of(configDir, "profiles.json");
    }

    public synchronized List<Connection> findAll() {
        return readState().profiles();
    }

    public synchronized Optional<Connection> findById(String id) {
        return readState().profiles().stream()
                .filter(profile -> profile.id().equals(id))
                .findFirst();
    }

    public synchronized Optional<String> findActiveId() {
        return Optional.ofNullable(readState().activeId());
    }

    public synchronized Connection save(Connection profile) {
        StoredState state = readState();
        String id = StringUtils.hasText(profile.id()) ? profile.id() : UUID.randomUUID().toString();
        Optional<Connection> existing = state.profiles().stream()
                .filter(current -> current.id().equals(id))
                .findFirst();
        Connection saved = existing
                .filter(current -> !StringUtils.hasText(profile.password()))
                .map(current -> profile.withId(id).withPassword(current.password()))
                .orElseGet(() -> profile.withId(id));
        List<Connection> profiles = existing.isPresent()
                ? state.profiles().stream().map(current -> current.id().equals(id) ? saved : current).toList()
                : Stream.concat(state.profiles().stream(), Stream.of(saved)).toList();
        writeState(new StoredState(profiles, state.activeId()));
        return saved;
    }

    public synchronized void delete(String id) {
        StoredState state = readState();
        List<Connection> profiles = state.profiles().stream()
                .filter(profile -> !profile.id().equals(id))
                .toList();
        String activeId = id.equals(state.activeId()) ? null : state.activeId();
        writeState(new StoredState(profiles, activeId));
    }

    public synchronized void setActiveId(String id) {
        StoredState state = readState();
        writeState(new StoredState(state.profiles(), id));
    }

    private StoredState readState() {
        if (!Files.exists(file)) {
            return new StoredState(List.of(), null);
        }
        try {
            return jsonMapper.readValue(file.toFile(), StoredState.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("No se pudo leer el archivo de perfiles: " + file, e);
        }
    }

    private void writeState(StoredState state) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "profiles", ".tmp");
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el archivo de perfiles: " + file, e);
        }
    }
}
