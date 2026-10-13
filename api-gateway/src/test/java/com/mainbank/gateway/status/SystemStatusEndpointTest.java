package com.mainbank.gateway.status;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /api/v1/system/status} as a client sees it: status code, JSON body and that routes are unaffected. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SystemStatusEndpointTest {

    private static final HttpServer LEDGER = healthy();
    private static final HttpServer RECEIPT = healthy();
    private static final HttpServer NOTIFICATION = healthy();
    /** The statement service is "down": nothing listens on this port. */
    private static final int DEAD_PORT = freePort();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.ledger-url", () -> "http://127.0.0.1:" + LEDGER.getAddress().getPort());
        registry.add("gateway.receipt-url", () -> "http://127.0.0.1:" + RECEIPT.getAddress().getPort());
        registry.add("gateway.notification-url", () -> "http://127.0.0.1:" + NOTIFICATION.getAddress().getPort());
        registry.add("gateway.statement-url", () -> "http://127.0.0.1:" + DEAD_PORT);
        registry.add("gateway.status-timeout", () -> "1s");
    }

    @Autowired TestRestTemplate rest;

    @AfterAll
    static void stop() {
        LEDGER.stop(0);
        RECEIPT.stop(0);
        NOTIFICATION.stop(0);
    }

    @Test
    void reportsDegradedWith503AndAPerServiceBodyWhenOneServiceIsDown() {
        ResponseEntity<String> r = rest.getForEntity("/api/v1/system/status", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(r.getBody())
                .contains("\"status\":\"DEGRADED\"")
                .contains("\"ledger\":{\"status\":\"UP\"")
                .contains("\"statement\":{\"status\":\"DOWN\"")
                .contains("\"detail\":\"unreachable\"")
                .contains("\"totalMs\":")
                .doesNotContain("\"up\":"); // no helper-method fields leaking into the JSON
    }

    @Test
    void theEndpointIsListedInTheGatewaysOwnOpenApiSpec() {
        String spec = rest.getForObject("/v3/api-docs", String.class);

        assertThat(spec).contains("/api/v1/system/status");
    }

    private static HttpServer healthy() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/actuator/health", exchange -> {
                byte[] out = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
