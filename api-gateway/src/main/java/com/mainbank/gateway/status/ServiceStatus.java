package com.mainbank.gateway.status;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Health of one service as seen from the gateway.
 *
 * @param status {@code UP} or {@code DOWN}
 * @param ms     how long the probe took, so a slow-but-alive service is visible too
 * @param detail why a service is DOWN ({@code unreachable}, {@code timeout}, {@code HTTP 503}, ...); absent when UP
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceStatus(String status, long ms, String detail) {

    static ServiceStatus up(long ms) {
        return new ServiceStatus("UP", ms, null);
    }

    static ServiceStatus down(long ms, String detail) {
        return new ServiceStatus("DOWN", ms, detail);
    }

    @JsonIgnore // a helper for code; "status" already says it in the JSON
    public boolean isUp() {
        return "UP".equals(status);
    }
}
