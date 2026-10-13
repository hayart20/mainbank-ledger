package com.mainbank.receipt.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.mainbank.receipt.config.MinioProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Protocol-level behaviour of {@link MinioClient} against a throw-away JDK HTTP server. */
class MinioClientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

    private HttpServer server;
    private MinioClient client;
    private final AtomicReference<HttpExchange> seen = new AtomicReference<>();
    private final AtomicReference<byte[]> seenBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenBody.set(exchange.getRequestBody().readAllBytes());
            seen.set(exchange);
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            // HEAD responses must not carry a body
            boolean head = "HEAD".equals(exchange.getRequestMethod());
            exchange.sendResponseHeaders(status, head || out.length == 0 ? -1 : out.length);
            if (!head && out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new MinioClient(new MinioProperties(url, "https://files.example.com:9443", "minioadmin", "minioadminpassword",
                "receipts-bucket", "us-east-1", Duration.ofMinutes(15), Duration.ofSeconds(5)), CLOCK);
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop(0);
    }

    @Test
    void putSendsSignedRequestWithContentTypeMetadataAndBody() {
        client.putObject("receipts/a b.pdf", "PDF".getBytes(StandardCharsets.UTF_8), "application/pdf", Map.of("sha256-details", "abc"));

        HttpExchange e = seen.get();
        assertThat(e.getRequestMethod()).isEqualTo("PUT");
        assertThat(e.getRequestURI().getRawPath()).isEqualTo("/receipts-bucket/receipts/a%20b.pdf");
        assertThat(seenBody.get()).isEqualTo("PDF".getBytes(StandardCharsets.UTF_8));
        assertThat(e.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/pdf");
        assertThat(e.getRequestHeaders().getFirst("x-amz-meta-sha256-details")).isEqualTo("abc");
        assertThat(e.getRequestHeaders().getFirst("x-amz-date")).isEqualTo("20260102T030405Z");
        assertThat(e.getRequestHeaders().getFirst("x-amz-content-sha256")).isEqualTo(SigV4.sha256Hex("PDF".getBytes(StandardCharsets.UTF_8)));
        assertThat(e.getRequestHeaders().getFirst("Authorization"))
                .startsWith("AWS4-HMAC-SHA256 Credential=minioadmin/20260102/us-east-1/s3/aws4_request,")
                .contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date;x-amz-meta-sha256-details,");
    }

    @Test
    void getReturnsBodyBytes() {
        responseBody = "hello";

        assertThat(client.getObject("k.pdf")).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(seen.get().getRequestMethod()).isEqualTo("GET");
    }

    @Test
    void errorResponseIsMappedToStatusAndS3Code() {
        status = 404;
        responseBody = "<Error><Code>NoSuchBucket</Code><Message>nope</Message></Error>";

        assertThatThrownBy(() -> client.putObject("k", new byte[]{1}, "application/pdf", Map.of()))
                .isInstanceOfSatisfying(MinioException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(404);
                    assertThat(ex.code()).isEqualTo("NoSuchBucket");
                    assertThat(ex.isNoSuchBucket()).isTrue();
                });
    }

    @Test
    void bucketExistsIsFalseOnBareHead404AndTrueOn200() {
        status = 404;
        assertThat(client.bucketExists()).isFalse();
        assertThat(seen.get().getRequestMethod()).isEqualTo("HEAD");

        status = 200;
        assertThat(client.bucketExists()).isTrue();
    }

    @Test
    void createBucketTreatsAlreadyOwnedAsSuccess() {
        status = 409;
        responseBody = "<Error><Code>BucketAlreadyOwnedByYou</Code></Error>";

        client.createBucket();

        assertThat(seen.get().getRequestMethod()).isEqualTo("PUT");
        assertThat(seen.get().getRequestURI().getRawPath()).isEqualTo("/receipts-bucket");
    }

    @Test
    void transportFailureIsWrappedWithoutStatus() {
        server.stop(0);

        assertThatThrownBy(() -> client.getObject("k"))
                .isInstanceOfSatisfying(MinioException.class, ex -> assertThat(ex.status()).isEqualTo(-1));
    }

    @Test
    void presignedUrlUsesPublicEndpointAndSignsResponseOverrides() {
        String url = client.presignGet("receipts/a.pdf", Duration.ofMinutes(15),
                Map.of("response-content-disposition", "attachment; filename=\"a.pdf\""));

        assertThat(url).startsWith("https://files.example.com:9443/receipts-bucket/receipts/a.pdf?")
                .contains("X-Amz-Expires=900")
                .contains("X-Amz-SignedHeaders=host")
                .contains("response-content-disposition=attachment%3B%20filename%3D%22a.pdf%22")
                .contains("X-Amz-Credential=minioadmin%2F20260102%2Fus-east-1%2Fs3%2Faws4_request")
                .containsPattern("X-Amz-Signature=[0-9a-f]{64}$");
    }
}
