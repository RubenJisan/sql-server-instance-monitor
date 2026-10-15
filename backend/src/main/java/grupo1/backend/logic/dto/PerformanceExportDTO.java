package grupo1.backend.logic.dto;

public record PerformanceExportDTO(String fileName, String contentType, byte[] content) {
}