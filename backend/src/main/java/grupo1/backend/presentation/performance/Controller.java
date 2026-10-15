package grupo1.backend.presentation.performance;

import java.util.List;

import grupo1.backend.logic.PerformanceService;
import grupo1.backend.logic.dto.PerformanceDTO;
import grupo1.backend.logic.dto.PerformanceExportDTO;
import grupo1.backend.logic.dto.SessionKillDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("performanceController")
@RequestMapping("/api/performance")
public class Controller {

    @Autowired
    private PerformanceService performanceService;

    @GetMapping("/overview")
    public PerformanceDTO overview(@RequestParam(required = false) String range) {
        return performanceService.overview(range);
    }

    @PostMapping("/sessions/{sessionId}/kill")
    public SessionKillDTO kill(@PathVariable int sessionId) {
        return performanceService.kill(sessionId);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) List<String> sections
    ) {
        PerformanceExportDTO file = performanceService.export(format, range, sections);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName())
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}