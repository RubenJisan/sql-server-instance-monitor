package grupo1.backend.presentation.instance;

import grupo1.backend.logic.InstanceService;
import grupo1.backend.logic.dto.InstanceDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("instanceController")
@RequestMapping("/api/instance")
public class Controller {

    @Autowired
    private InstanceService instanceService;

    @GetMapping("/overview")
    public InstanceDTO overview() {
        return instanceService.overview();
    }
}
