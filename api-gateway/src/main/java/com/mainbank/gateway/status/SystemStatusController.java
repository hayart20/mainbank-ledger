package com.mainbank.gateway.status;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
class SystemStatusController {

    private final SystemStatusService status;

    SystemStatusController(SystemStatusService status) {
        this.status = status;
    }

    @Operation(summary = "Health of every service, checked in parallel",
            description = "200 when all services are UP; 503 when any is not (DEGRADED or DOWN). "
                    + "The body always lists each service with its status and probe time.")
    @GetMapping("/status")
    ResponseEntity<SystemStatus> status() {
        SystemStatus result = status.check();
        return ResponseEntity.status(SystemStatus.UP.equals(result.status()) ? 200 : 503).body(result);
    }
}
