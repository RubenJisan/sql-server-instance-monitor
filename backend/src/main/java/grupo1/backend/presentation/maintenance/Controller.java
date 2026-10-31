package grupo1.backend.presentation.maintenance;

import grupo1.backend.logic.MaintenanceService;
import grupo1.backend.logic.dto.MaintenanceDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("maintenanceController")
@RequestMapping("/api/maintenance")
@RequiredArgsConstructor
public class Controller {
    private final MaintenanceService service;

    @GetMapping("/overview")
    public MaintenanceDTO overview(@RequestParam(required = false) String database) {
        return service.getOverview(database);
    }

    @PostMapping("/statistics")
    public MaintenanceDTO.Result statistics(@Valid @RequestBody MaintenanceDTO.Request request) {
        return service.run("statistics", request);
    }

    @PostMapping("/refresh")
    public MaintenanceDTO.Result refresh(@Valid @RequestBody MaintenanceDTO.Request request) {
        return service.run("refresh", request);
    }
}
