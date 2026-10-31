package grupo1.backend.presentation.connection;

import grupo1.backend.logic.Connection;
import grupo1.backend.logic.ConnectionService;
import grupo1.backend.logic.dto.ConnectionOverviewDTO;
import grupo1.backend.logic.dto.ConnectionTestDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController("connectionController")
@RequestMapping("/api/connections")
public class Controller {

    @Autowired
    private ConnectionService connectionService;

    @GetMapping
    public ConnectionOverviewDTO overview() {
        return connectionService.getOverview();
    }

    @PostMapping
    public Connection save(@Valid @RequestBody Connection profile) {
        return connectionService.save(profile);
    }

    @PostMapping("/test")
    public ConnectionTestDTO test(@Valid @RequestBody Connection profile) {
        return connectionService.test(profile);
    }

    @PostMapping("/{id}/activate")
    public Connection activate(@PathVariable String id) {
        return connectionService.activate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        connectionService.delete(id);
    }
}
