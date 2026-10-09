package grupo1.backend.logic.dto;

import java.util.List;

public record BackupRunDTO(List<Result> results) {

    public record Result(String database, boolean ok, String file, String message) {
    }
}
