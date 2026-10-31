package grupo1.backend.presentation.maintenance;

import java.time.Instant;
import grupo1.backend.logic.MaintenanceService;
import grupo1.backend.logic.NoActiveConnectionException;
import grupo1.backend.logic.RestExceptionHandler;
import grupo1.backend.logic.dto.MaintenanceDTO.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MaintenanceControllerTests {
    private final MaintenanceService service = mock(MaintenanceService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Controller(service))
                .setControllerAdvice(new RestExceptionHandler()).build();
    }

    @Test
    void overviewReportsMissingActiveConnection() throws Exception {
        when(service.getOverview(null)).thenThrow(new NoActiveConnectionException());
        mvc.perform(get("/api/maintenance/overview"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void requestWithoutConfirmationIsRejectedBeforeCallingService() throws Exception {
        mvc.perform(post("/api/maintenance/statistics").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"profileId":"profile","database":"Example","objectId":12,
                         "schema":"dbo","name":"sample"}
                        """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("confirmed")));
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonHasReadableError() throws Exception {
        mvc.perform(post("/api/maintenance/statistics").contentType(MediaType.APPLICATION_JSON)
                .content("{bad json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void statisticsAcceptsFullScanAndReturnsSuccessfulResult() throws Exception {
        when(service.run(eq("statistics"), any())).thenReturn(new Result(true, "statistics", "Example",
                "dbo.sample", "Estadísticas actualizadas", Instant.now()));
        mvc.perform(post("/api/maintenance/statistics").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"profileId":"profile","database":"Example","objectId":12,
                         "schema":"dbo","name":"sample","confirmed":true,"fullScan":true}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        verify(service).run(eq("statistics"), argThat(request -> Boolean.TRUE.equals(request.fullScan())));
    }

    @Test
    void refreshPreservesFailedOperationResultInJson() throws Exception {
        when(service.run(eq("refresh"), any())).thenReturn(new Result(false, "refresh", "Example",
                "dbo.sample", "Dependencia inexistente", Instant.now()));
        mvc.perform(post("/api/maintenance/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"profileId":"profile","database":"Example","objectId":12,
                         "schema":"dbo","name":"sample","confirmed":true}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("Dependencia inexistente"));
    }
}
