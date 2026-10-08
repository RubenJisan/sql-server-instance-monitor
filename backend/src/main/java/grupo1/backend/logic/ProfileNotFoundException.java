package grupo1.backend.logic;

public class ProfileNotFoundException extends RuntimeException {

    public ProfileNotFoundException(String id) {
        super("No existe un perfil de conexión con el id " + id);
    }
}
