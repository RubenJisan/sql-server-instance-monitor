package grupo1.backend.presentation.audit;

import grupo1.backend.logic.AuditService;
import grupo1.backend.logic.dto.AuditDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("auditController")
@RequestMapping("/api/audit")
public class Controller {

    @Autowired
    private AuditService auditService;

    @GetMapping("/overview")
    public AuditDTO overview() {
        return auditService.overview();
    }
}