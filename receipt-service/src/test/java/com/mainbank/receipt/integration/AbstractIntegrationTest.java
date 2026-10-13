package com.mainbank.receipt.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;

/**
 * Real Postgres, Kafka and MinIO, shared by all integration tests in the JVM (started once, reaped by Ryuk).
 * The receipt service runs as in production, only with short timeouts so failure paths finish in seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIntegrationTest {

    static final String TOPIC = "ledger.transfer.completed";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    @SuppressWarnings("resource")
    static final GenericContainer<?> MINIO = new GenericContainer<>("cgr.dev/chainguard/minio:latest")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadminpassword")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofSeconds(60)));

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        POSTGRES.start();
        MINIO.start();
        KAFKA.start();
    }

    static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // the container's own JDBC url already carries query parameters; currentSchema selects the service's schema
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=receipt");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("minio.endpoint", AbstractIntegrationTest::minioEndpoint);
        registry.add("minio.public-endpoint", AbstractIntegrationTest::minioEndpoint);
        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadminpassword");
        // short so a paused MinIO fails an upload in seconds instead of the production 10s
        registry.add("minio.attempt-timeout", () -> "2s");
        registry.add("receipt.retry-interval", () -> "PT2S");
        registry.add("receipt.retry-min-age", () -> "1s");
    }
}
