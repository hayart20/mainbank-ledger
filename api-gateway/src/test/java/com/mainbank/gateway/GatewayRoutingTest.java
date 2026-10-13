package com.mainbank.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway as deployed, in front of three stub services: real requests are proxied over real sockets, so routing,
 * prefix stripping, header and body pass-through, the Host header on the OpenAPI routes and the fail-fast
 * behaviour for a down or slow service are all exercised end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    /** What a stub service saw: its name plus the request line and the headers that matter. */
    record Seen(String service, String method, String path, String host, String idempotencyKey, String body) { }

    private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

    private static final HttpServer LEDGER = stub("ledger");
    private static final HttpServer RECEIPT = stub("receipt");
    private static final HttpServer STATEMENT = stub("statement");
    /** A port with nothing listening on it, for the "service is down" case. */
    private static final int DEAD_PORT = freePort();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.ledger-url", () -> "http://localhost:" + LEDGER.getAddress().getPort());
        registry.add("gateway.receipt-url", () -> "http://localhost:" + RECEIPT.getAddress().getPort());
        // the statement service is "down" in these tests
        registry.add("gateway.statement-url", () -> "http://localhost:" + DEAD_PORT);
        // short, so the slow-service test finishes quickly
        registry.add("spring.cloud.gateway.server.webmvc.http-client.read-timeout", () -> "1s");
    }

    @LocalServerPort int port;

    /** Own client with generous timeouts: it must not race the gateway's 1 s read timeout set above. */
    TestRestTemplate rest;

    @BeforeEach
    void client() {
        rest = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + port)
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(20)));
    }

    @AfterAll
    static void stopStubs() {
        LEDGER.stop(0);
        RECEIPT.stop(0);
        STATEMENT.stop(0);
    }

    @Test
    void accountsAndTransfersGoToTheLedgerWithPathAndBodyIntact() {
        SEEN.clear();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Idempotency-Key", "11111111-2222-3333-4444-555555555555");

        ResponseEntity<String> created = rest.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>("{\"amount\":\"15.00\"}", headers), String.class);
        ResponseEntity<String> account = rest.getForEntity("/api/v1/accounts/abc", String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // response status, a custom response header and the body all come back through the gateway
        assertThat(created.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(created.getBody()).contains("\"service\":\"ledger\"");
        assertThat(account.getBody()).contains("\"service\":\"ledger\"").contains("\"path\":\"/api/v1/accounts/abc\"");

        Seen post = SEEN.stream().filter(s -> s.method().equals("POST")).findFirst().orElseThrow();
        assertThat(post.service()).isEqualTo("ledger");
        assertThat(post.path()).isEqualTo("/api/v1/transfers");
        assertThat(post.body()).isEqualTo("{\"amount\":\"15.00\"}");
        assertThat(post.idempotencyKey()).isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    void receiptsGoToTheReceiptService() {
        ResponseEntity<String> r = rest.getForEntity("/api/v1/receipts/7b9c0d9e", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"service\":\"receipt\"").contains("\"path\":\"/api/v1/receipts/7b9c0d9e\"");
    }

    @Test
    void downstreamErrorsPassThroughUnchanged() {
        ResponseEntity<String> r = rest.getForEntity("/api/v1/receipts/not-ready", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(r.getBody()).contains("RECEIPT_NOT_READY");
    }

    @Test
    void openApiSpecsAreRoutedWithThePrefixStrippedAndTheClientsHostKept() {
        SEEN.clear();

        ResponseEntity<String> r = rest.getForEntity("/docs/ledger/v3/api-docs", String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Seen seen = SEEN.get(0);
        assertThat(seen.service()).isEqualTo("ledger");
        assertThat(seen.path()).isEqualTo("/v3/api-docs");            // /docs/ledger stripped
        // PreserveHost: the service sees the address the client used (the gateway), not its own
        assertThat(seen.host()).isEqualTo("localhost:" + port);
    }

    @Test
    void onlyTheSpecIsExposedUnderDocsAndUnknownPathsAre404() {
        assertThat(rest.getForEntity("/docs/ledger/actuator/env", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/v1/unknown", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/internal/anything", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The gateway adds no failure translation: a service that is down surfaces as Spring's generic 500. What
     * matters, and is asserted, is that it fails fast instead of hanging and that nothing else is affected.
     */
    @Test
    void aServiceThatIsDownFailsFastWithAServerError() {
        long start = System.nanoTime();

        ResponseEntity<String> r = rest.getForEntity("/api/v1/accounts/abc/statement", String.class);

        assertThat(r.getStatusCode().is5xxServerError()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000); // 2 s connect timeout, not a hang
    }

    @Test
    void aServiceThatDoesNotAnswerInTimeFailsAtTheReadTimeoutNotAtTheServicesPace() {
        long start = System.nanoTime();

        ResponseEntity<String> r = rest.getForEntity("/api/v1/receipts/slow", String.class);

        assertThat(r.getStatusCode().is5xxServerError()).isTrue();
        // bounded by the 1 s read timeout of this test, not the stub's 5 s sleep
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(4_000);
    }

    @Test
    void theGatewayStaysUpAndOtherRoutesStillWorkAfterFailures() {
        rest.getForEntity("/api/v1/accounts/abc/statement", String.class);  // down
        rest.getForEntity("/api/v1/receipts/slow", String.class);            // too slow

        assertThat(rest.getForEntity("/actuator/health", String.class).getBody()).contains("UP");
        assertThat(rest.getForEntity("/api/v1/accounts/abc", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- stub services ----

    private static HttpServer stub(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(name, exchange));
            // one thread per request: the deliberately slow handler must not block the other requests
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("cannot start stub " + name, e);
        }
    }

    private static void handle(String service, HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        SEEN.add(new Seen(service, exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Host"),
                exchange.getRequestHeaders().getFirst("X-Idempotency-Key"), body));

        int status = 200;
        String contentType = "application/json";
        String payload = "{\"service\":\"" + service + "\",\"path\":\"" + path + "\"}";
        if (path.equals("/api/v1/receipts/slow")) {
            sleep(5_000);
        } else if (path.equals("/api/v1/receipts/not-ready")) {
            status = 409;
            contentType = "application/problem+json";
            payload = "{\"status\":409,\"code\":\"RECEIPT_NOT_READY\"}";
        } else if (exchange.getRequestMethod().equals("POST")) {
            status = 201;
            exchange.getResponseHeaders().add("Idempotent-Replayed", "true");
        }
        byte[] out = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
