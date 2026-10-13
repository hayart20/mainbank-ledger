package com.mainbank.statement.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.statement.service.StatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TransferCompletedConsumerTest {

    @Mock StatementService statements;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private TransferCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TransferCompletedConsumer(statements, json);
    }

    @Test
    void selfContainedMessageIsHandedToTheService() throws Exception {
        TransferCompletedMessage message = new TransferCompletedMessage(UUID.randomUUID(), "TRANSFER",
                Instant.parse("2026-01-02T03:04:05.123Z"), UUID.randomUUID(), "AM11", "Hayk",
                UUID.randomUUID(), "AM22", "Bob", new BigDecimal("12.30"), "AMD");

        consumer.onTransferCompleted(json.writeValueAsString(message));

        verify(statements).onTransferCompleted(message);
    }

    /** The event exactly as the ledger publishes it, plus a future field: fields not needed here are ignored. */
    @Test
    void readsTheLedgersPublishedFormatAndIgnoresFieldsItDoesNotKnow() {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID source = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID target = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        String payload = """
                {"schemaVersion":1,"transferId":"%s","type":"TRANSFER","createdAt":"2026-01-02T03:04:05.123Z",
                 "sourceAccountId":"%s","sourceIban":"AM11","sourceOwner":"Hayk",
                 "targetAccountId":"%s","targetIban":"AM22","targetOwner":"Bob",
                 "amount":12.30,"currency":"AMD","someFutureField":{"x":1}}""".formatted(id, source, target);

        consumer.onTransferCompleted(payload);

        verify(statements).onTransferCompleted(new TransferCompletedMessage(id, "TRANSFER",
                Instant.parse("2026-01-02T03:04:05.123Z"), source, "AM11", "Hayk", target, "AM22", "Bob",
                new BigDecimal("12.30"), "AMD"));
    }

    @Test
    void legacyMalformedOrIdlessMessagesAreRejectedForTheDeadLetterTopic() {
        assertThatThrownBy(() -> consumer.onTransferCompleted("{\"transferId\":\"" + UUID.randomUUID() + "\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.onTransferCompleted("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.onTransferCompleted("{}")).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(statements);
    }
}
