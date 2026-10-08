package grupo1.backend.logic;

public class NoActiveConnectionException extends RuntimeException {

    public NoActiveConnectionException() {
        super("No hay una instancia activa. Selecciona o configura una conexión.");
    }
}
