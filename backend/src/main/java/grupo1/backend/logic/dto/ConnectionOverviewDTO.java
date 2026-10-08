package grupo1.backend.logic.dto;

import grupo1.backend.logic.Connection;

import java.util.List;

public record ConnectionOverviewDTO(List<Connection> profiles, String activeId) {
}
