package com.mainbank.receipt.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.receipt.messaging.TransferCompletedMessage;
import com.mainbank.receipt.dto.ReceiptLinkResponse;
import com.mainbank.receipt.service.ReceiptData;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The receipt pipeline against real infrastructure: an event on Kafka becomes a PDF in MinIO that can be
 * downloaded through a pre-signed URL, and the failure paths (MinIO outage, redelivery, poison message) hold.
 */
class ReceiptIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;

    private static TransferCompletedMessage event(String amount) {
        return new TransferCompletedMessage(UUID.randomUUID(), "TRANSFER", Instant.now().truncatedTo(ChronoUnit.MILLIS),
                "AM11103000000000001000000001", "Hayk Հայկ",
                "AM22103000000000001000000002", "Aram Harutyunyan", new BigDecimal(amount), "AMD");
    }

    /**
     * Publishes the event the way the ledger does: every field of its contract (account ids and schemaVersion
     * included) plus a field this service has never heard of, which it must ignore.
     */
    private void publish(TransferCompletedMessage m) throws JsonProcessingException, ExecutionException,
            InterruptedException, TimeoutException {
        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("schemaVersion", 1);
        wire.put("transferId", m.transferId());
        wire.put("type", m.type());
        wire.put("createdAt", m.createdAt().toString());
        wire.put("sourceAccountId", UUID.randomUUID());
        wire.put("sourceIban", m.sourceIban());
        wire.put("sourceOwner", m.sourceOwner());
        wire.put("targetAccountId", UUID.randomUUID());
        wire.put("targetIban", m.targetIban());
        wire.put("targetOwner", m.targetOwner());
        wire.put("amount", m.amount());
        wire.put("currency", m.currency());
        wire.put("someFutureField", Map.of("added", "later"));
        kafka.send(TOPIC, m.transferId().toString(), json.writeValueAsString(wire)).get(15, TimeUnit.SECONDS);
    }

    private String status(UUID id) {
        List<String> rows = jdbc.queryForList("select status from receipts where transfer_id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void awaitStatus(UUID id, String expected, Duration atMost) {
        await().atMost(atMost).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(expected));
    }

    @Test
    void eventBecomesAPdfInMinioDownloadableThroughAPresignedUrl() throws Exception {
        TransferCompletedMessage m = event("12.34");

        publish(m);
        awaitStatus(m.transferId(), "READY", Duration.ofSeconds(60));

        ReceiptLinkResponse link = rest.getForObject("/api/v1/receipts/" + m.transferId(), ReceiptLinkResponse.class);
        assertThat(link.sha256()).isEqualTo(ReceiptData.of(m).detailsHash());
        assertThat(link.expiresAt()).isAfter(Instant.now().plusSeconds(14 * 60));

        // a URI object, not a String: RestTemplate must not re-encode the signed query string
        ResponseEntity<byte[]> download = rest.getForEntity(URI.create(link.url()), byte[].class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getFirst("Content-Disposition"))
                .contains("receipt-" + m.transferId() + ".pdf");
        byte[] pdf = download.getBody();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");

        // the database row points at the stored object and records the digest of exactly these bytes
        Map<String, Object> row = jdbc.queryForMap(
                "select object_key, details_sha256, pdf_sha256 from receipts where transfer_id = ?", m.transferId());
        assertThat(row.get("object_key")).isEqualTo("receipts/" + m.transferId() + ".pdf");
        assertThat(((String) row.get("pdf_sha256")).trim()).isEqualTo(ReceiptData.sha256Hex(pdf));
        assertThat(((String) row.get("details_sha256")).trim()).isEqualTo(link.sha256());

        ResponseEntity<byte[]> streamed = rest.getForEntity("/api/v1/receipts/" + m.transferId() + "?mode=stream", byte[].class);
        assertThat(streamed.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(streamed.getBody()).isEqualTo(pdf);
    }

    @Test
    void aTamperedPresignedUrlIsRejectedByMinio() throws Exception {
        TransferCompletedMessage m = event("5.00");
        publish(m);
        awaitStatus(m.transferId(), "READY", Duration.ofSeconds(60));
        String url = rest.getForObject("/api/v1/receipts/" + m.transferId(), ReceiptLinkResponse.class).url();

        char last = url.charAt(url.length() - 1);
        String tampered = url.substring(0, url.length() - 1) + (last == '0' ? '1' : '0');

        assertThat(rest.getForEntity(URI.create(tampered), byte[].class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void redeliveredEventNeverCreatesASecondReceiptOrRegeneratesTheFirst() throws Exception {
        TransferCompletedMessage m = event("7.50");

        publish(m);
        awaitStatus(m.transferId(), "READY", Duration.ofSeconds(60));
        publish(m);
        publish(m);

        // stays at exactly one row and one successful attempt while the duplicates are consumed
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from receipts where transfer_id = ?", Integer.class, m.transferId()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("select attempts from receipts where transfer_id = ?", Integer.class, m.transferId()))
                    .isEqualTo(1);
        });
    }

    @Test
    void receiptSurvivesMinioOutageAndIsGeneratedByTheRetryJob() throws Exception {
        TransferCompletedMessage m = event("99.99");

        MINIO.getDockerClient().pauseContainerCmd(MINIO.getContainerId()).exec();
        try {
            publish(m);
            // the upload times out and is recorded as FAILED; nothing is lost and nothing crashes
            awaitStatus(m.transferId(), "FAILED", Duration.ofSeconds(40));
        } finally {
            MINIO.getDockerClient().unpauseContainerCmd(MINIO.getContainerId()).exec();
        }

        // MinIO is back: the scheduled retry job produces the receipt without any new event
        awaitStatus(m.transferId(), "READY", Duration.ofSeconds(60));
        assertThat(jdbc.queryForObject("select attempts from receipts where transfer_id = ?", Integer.class, m.transferId()))
                .isGreaterThanOrEqualTo(2);
        ReceiptLinkResponse link = rest.getForObject("/api/v1/receipts/" + m.transferId(), ReceiptLinkResponse.class);
        assertThat(rest.getForEntity(URI.create(link.url()), byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void poisonMessageIsDeadLetteredAndTheConsumerKeepsWorking() throws Exception {
        String poison = "{\"this\":\"is not a transfer\" " + UUID.randomUUID();

        kafka.send(TOPIC, "poison-" + UUID.randomUUID(), poison).get(15, TimeUnit.SECONDS);

        assertThat(dlt(poison)).as("poison message reached the dead-letter topic").isTrue();

        // the listener is still alive: a valid event right after it is processed normally
        TransferCompletedMessage m = event("1.00");
        publish(m);
        awaitStatus(m.transferId(), "READY", Duration.ofSeconds(60));
    }

    @Test
    void unknownReceiptIs404WithAStableCode() {
        ResponseEntity<Map> r = rest.getForEntity("/api/v1/receipts/" + UUID.randomUUID(), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code")).isEqualTo("RECEIPT_NOT_FOUND");
    }

    @Test
    void receiptThatIsNotReadyIs409() {
        UUID id = UUID.randomUUID();
        // attempts = max-attempts: the retry job leaves it alone, so it stays not ready for the assertion
        jdbc.update("""
                insert into receipts (transfer_id, type, transfer_created_at, source_iban, source_owner, target_iban,
                                      target_owner, amount, currency, status, attempts)
                values (?, 'TRANSFER', now(), 'AM11', 'Hayk', 'AM22', 'Bob', 10.00, 'AMD', 'FAILED', 5)""", id);

        ResponseEntity<Map> r = rest.getForEntity("/api/v1/receipts/" + id, Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("RECEIPT_NOT_READY");
    }

    /** Reads the dead-letter topic from the beginning until a record with this exact payload shows up. */
    private boolean dlt(String payload) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (payload.equals(r.value())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
