package com.mainbank.gateway.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** The aggregation against real HTTP servers: outcomes per service, and that the probes really run in parallel. */
class SystemStatusServiceTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    /** A service answering {@code /actuator/health} with the given status and body after {@code delayMs}. */
    private String service(int status, String body, long delayMs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String up(long delayMs) throws IOException {
        return service(200, "{\"status\":\"UP\"}", delayMs);
    }

    private static String deadUrl() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort(); // closed again: nothing is listening
        }
    }

    private SystemStatusService checker(Duration timeout, String ledger, String receipt, String statement, String notification) {
        Map<String, String> services = new LinkedHashMap<>();
        services.put("ledger", ledger);
        services.put("receipt", receipt);
        services.put("statement", statement);
        services.put("notification", notification);
        return new SystemStatusService(services, timeout, new ObjectMapper());
    }

    @Test
    void everyServiceUpIsUp() throws Exception {
        var status = checker(Duration.ofSeconds(2), up(0), up(0), up(0), up(0)).check();

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.services()).containsOnlyKeys("ledger", "receipt", "statement", "notification");
        assertThat(status.services().values()).allMatch(ServiceStatus::isUp);
        assertThat(status.services().get("ledger").detail()).isNull();
    }

    @Test
    void oneUnreachableServiceMakesTheSystemDegradedAndTheOthersStayUp() throws Exception {
        var status = checker(Duration.ofSeconds(2), up(0), up(0), deadUrl(), up(0)).check();

        assertThat(status.status()).isEqualTo("DEGRADED");
        assertThat(status.services().get("statement").status()).isEqualTo("DOWN");
        assertThat(status.services().get("statement").detail()).isEqualTo("unreachable");
        assertThat(status.services().get("ledger").isUp()).isTrue();
        assertThat(status.services().get("notification").isUp()).isTrue();
    }

    @Test
    void aServiceAnsweringWithAnErrorStatusOrAnUnhealthyBodyIsDown() throws Exception {
        var status = checker(Duration.ofSeconds(2),
                up(0),
                service(503, "{\"status\":\"DOWN\"}", 0),
                service(200, "{\"status\":\"OUT_OF_SERVICE\"}", 0),
                up(0)).check();

        assertThat(status.status()).isEqualTo("DEGRADED");
        assertThat(status.services().get("receipt").detail()).isEqualTo("HTTP 503");
        assertThat(status.services().get("statement").detail()).isEqualTo("reports OUT_OF_SERVICE");
    }

    @Test
    void aServiceThatDoesNotAnswerInTimeIsDownAtTheTimeoutNotAtItsOwnPace() throws Exception {
        var status = checker(Duration.ofMillis(500), up(0), up(0), up(0), up(4_000)).check();

        assertThat(status.status()).isEqualTo("DEGRADED");
        assertThat(status.services().get("notification").detail()).isEqualTo("timeout");
        assertThat(status.totalMs()).isLessThan(2_500); // bounded by the 500 ms timeout, not the 4 s sleep
    }

    @Test
    void probesRunInParallelSoTheCheckTakesAsLongAsTheSlowestServiceNotTheSum() throws Exception {
        // four services of 700 ms each: sequentially this would take at least 2.8 s
        var status = checker(Duration.ofSeconds(5), up(700), up(700), up(700), up(700)).check();

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.services().values()).allSatisfy(s -> assertThat(s.ms()).isGreaterThanOrEqualTo(650));
        assertThat(status.totalMs()).isGreaterThanOrEqualTo(650).isLessThan(2_000);
    }

    @Test
    void everythingDownIsDown() throws Exception {
        var status = checker(Duration.ofSeconds(1), deadUrl(), deadUrl(), deadUrl(), deadUrl()).check();

        assertThat(status.status()).isEqualTo("DOWN");
        assertThat(status.services().values()).allMatch(s -> !s.isUp());
    }
}
