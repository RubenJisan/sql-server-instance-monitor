package grupo1.backend.presentation.backup;


import grupo1.backend.logic.dto.BackupDTO;
import grupo1.backend.logic.dto.BackupRunDTO;
import grupo1.backend.logic.BackupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("backupController")
@RequestMapping("/api/backups")
public class Controller {

    @Autowired
    private BackupService backupService;

    @GetMapping("/overview")
    public BackupDTO overview(@RequestParam(required = false) String database) {
        return backupService.getOverview(database);
    }

    @PostMapping("/full")
    public BackupRunDTO runFull(@RequestParam(required = false) String database) {
        return backupService.runFull(database);
    }
}
