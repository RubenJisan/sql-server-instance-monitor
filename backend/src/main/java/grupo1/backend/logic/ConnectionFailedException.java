package grupo1.backend.logic;

public class ConnectionFailedException extends RuntimeException {

    public ConnectionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
