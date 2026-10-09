package grupo1.backend.logic;

import java.io.UncheckedIOException;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(NoActiveConnectionException.class)
    public ResponseEntity<ApiError> handleNoActiveConnection(NoActiveConnectionException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ConnectionFailedException.class)
    public ResponseEntity<ApiError> handleConnectionFailed(ConnectionFailedException e) {
        return build(HttpStatus.BAD_GATEWAY, "No se pudo conectar a la instancia: " + e.getMessage());
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<ApiError> handleProfileNotFound(ProfileNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "Datos inválidos. " + details);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDataAccess(DataAccessException e) {
        String reason = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error al ejecutar la consulta: " + reason);
    }

    @ExceptionHandler({IllegalStateException.class, UncheckedIOException.class})
    public ResponseEntity<ApiError> handleStorage(RuntimeException e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(message));
    }

    @ExceptionHandler(BackupFailedException.class)
    public ResponseEntity<Map<String, String>> backupFailed(BackupFailedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("message", e.getMessage()));
    }
}
