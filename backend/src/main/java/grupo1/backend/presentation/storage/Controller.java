package grupo1.backend.presentation.storage;

import grupo1.backend.logic.StorageService;
import grupo1.backend.logic.dto.StorageDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("storageController")
@RequestMapping("/api/storage")
public class Controller {

    @Autowired
    private StorageService storageService;

    @GetMapping("/overview")
    public StorageDTO overview(@RequestParam(required = false) String database) {
        return storageService.overview(database);
    }
}
