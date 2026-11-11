package cc.projectargus.workbench.web;

import cc.projectargus.workbench.chat.StatusService;
import cc.projectargus.workbench.web.dto.StatusResponseDto;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes runtime and model readiness telemetry at {@code GET /api/status}.
 */
@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class StatusController {

    private final StatusService statusService;

    public StatusController(StatusService statusService) {
        this.statusService = Objects.requireNonNull(statusService, "statusService");
    }

    @GetMapping("/status")
    public StatusResponseDto getStatus() {
        return statusService.getStatus();
    }
}
