package com.mainbank.gateway.status;

import java.time.Instant;
import java.util.Map;

/**
 * Aggregated health of the whole system.
 *
 * @param status  {@code UP} when every service is up, {@code DOWN} when none is, {@code DEGRADED} otherwise
 * @param totalMs wall-clock time of the whole check: the slowest probe, not the sum, because they run in parallel
 */
public record SystemStatus(String status, Instant checkedAt, long totalMs, Map<String, ServiceStatus> services) {

    public static final String UP = "UP";
    public static final String DEGRADED = "DEGRADED";
    public static final String DOWN = "DOWN";
}
