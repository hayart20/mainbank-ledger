package com.mainbank.ledger.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** The producer only writes the outbox row; publishing to Kafka is {@link OutboxRelayTest}'s concern. */
@ExtendWith(MockitoExtension.class)
class TransferEntityEventProducerTest
{

    private static final String TOPIC = "ledger.transfer.completed";

    @Mock OutboxRepository outbox;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private TransferEventProducer producer;
    private final UUID id = UUID.randomUUID();
    private final TransferCompletedMessage message = new TransferCompletedMessage(TransferCompletedMessage.SCHEMA_VERSION,
            id, "TRANSFER", Instant.parse("2026-01-02T03:04:05.123Z"), UUID.randomUUID(), "AM11", "Hayk",
            UUID.randomUUID(), "AM22", "Bob", new BigDecimal("12.30"), "AMD");

    @BeforeEach
    void setUp() {
        producer = new TransferEventProducer(outbox, json, TOPIC);
    }

    @Test
    void recordsTheFullMessageInTheOutboxKeyedByTransferId() throws Exception {
        producer.record(message);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        OutboxEvent event = saved.getValue();
        assertThat(event.getTopic()).isEqualTo(TOPIC);
        assertThat(event.getAggregateId()).isEqualTo(id);
        assertThat(event.getPublishedAt()).isNull();

        JsonNode node = json.readTree(event.getPayload());
        assertThat(node.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(node.get("transferId").asText()).isEqualTo(id.toString());
        assertThat(node.get("sourceIban").asText()).isEqualTo("AM11");
        assertThat(node.get("targetOwner").asText()).isEqualTo("Bob");
        assertThat(json.readValue(event.getPayload(), TransferCompletedMessage.class)).isEqualTo(message);
    }

    /**
     * The published contract of ledger.transfer.completed. Consumers (in other repositories or modules) read these
     * exact names: adding a field is fine, renaming or removing one breaks them, so this test must fail first.
     */
    @Test
    void publishedFieldNamesAreTheDocumentedContract() throws Exception {
        producer.record(message);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        JsonNode node = json.readTree(saved.getValue().getPayload());

        var names = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("schemaVersion", "transferId", "type", "createdAt",
                "sourceAccountId", "sourceIban", "sourceOwner", "targetAccountId", "targetIban", "targetOwner",
                "amount", "currency");
        assertThat(node.get("createdAt").asText()).isEqualTo("2026-01-02T03:04:05.123Z"); // ISO-8601, not an epoch number
        assertThat(node.get("amount").decimalValue()).isEqualByComparingTo("12.30");
    }
}
