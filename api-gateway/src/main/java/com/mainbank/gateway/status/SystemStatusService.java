package com.mainbank.gateway.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks every service for its health <em>at the same time</em> and combines the answers.
 * <p>
 * Each probe is a blocking HTTP call, which is exactly the case virtual threads are for: one cheap virtual thread
 * per service, so the whole check takes as long as the slowest service (bounded by the timeout) instead of the sum
 * of all of them, and a hung service cannot hold up the report on the others.
 */
public class SystemStatusService {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);

    private final Map<String, String> services;
    private final Duration timeout;
    private final ObjectMapper json;
    private final HttpClient http;

    /**
     * @param services service name to base URL, in the order they should be reported
     * @param timeout  per-service limit for connecting and for the answer
     */
    public SystemStatusService(Map<String, String> services, Duration timeout, ObjectMapper json) {
        this.services = new LinkedHashMap<>(services); // keeps the reporting order
        this.timeout = timeout;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    public SystemStatus check() {
        long start = System.nanoTime();
        Map<String, ServiceStatus> results = new LinkedHashMap<>();

        // newVirtualThreadPerTaskExecutor: a new virtual thread per probe; closing the executor waits for them all
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<ServiceStatus>> pending = new LinkedHashMap<>();
            services.forEach((name, url) -> pending.put(name, executor.submit(() -> probe(url))));
            pending.forEach((name, future) -> results.put(name, await(name, future)));
        }

        long up = results.values().stream().filter(ServiceStatus::isUp).count();
        String overall = up == results.size() ? SystemStatus.UP : up == 0 ? SystemStatus.DOWN : SystemStatus.DEGRADED;
        return new SystemStatus(overall, Instant.now(), (System.nanoTime() - start) / 1_000_000, results);
    }

    private ServiceStatus probe(String baseUrl) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                    .timeout(timeout).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long ms = elapsed(start);
            if (response.statusCode() != 200) {
                return ServiceStatus.down(ms, "HTTP " + response.statusCode());
            }
            String reported = json.readTree(response.body()).path("status").asText("UNKNOWN");
            return "UP".equals(reported) ? ServiceStatus.up(ms) : ServiceStatus.down(ms, "reports " + reported);
        } catch (HttpConnectTimeoutException e) {
            // the JDK reports a failed connect (nothing listening, name not resolvable) as a connect timeout
            return ServiceStatus.down(elapsed(start), "unreachable");
        } catch (HttpTimeoutException e) {
            return ServiceStatus.down(elapsed(start), "timeout");
        } catch (IOException e) {
            return ServiceStatus.down(elapsed(start), "unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ServiceStatus.down(elapsed(start), "interrupted");
        } catch (RuntimeException e) {
            log.warn("Unexpected failure probing {}: {}", baseUrl, e.toString());
            return ServiceStatus.down(elapsed(start), "error");
        }
    }

    /** Every probe has its own timeout; this is only a backstop so a bug can never hang the endpoint. */
    private ServiceStatus await(String name, Future<ServiceStatus> future) {
        long backstopMs = timeout.toMillis() * 2 + 1_000;
        try {
            return future.get(backstopMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ServiceStatus.down(backstopMs, "timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ServiceStatus.down(0, "interrupted");
        } catch (ExecutionException e) {
            log.warn("Probe of {} failed: {}", name, e.getCause().toString());
            return ServiceStatus.down(0, "error");
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
