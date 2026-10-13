package com.mainbank.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.notification.service.NotificationService;
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

    @Mock NotificationService notifications;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private TransferCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TransferCompletedConsumer(notifications, json);
    }

    @Test
    void selfContainedMessageIsHandedToTheService() throws Exception {
        TransferCompletedMessage message = new TransferCompletedMessage(UUID.randomUUID(), "TRANSFER",
                "AM11", "Anna",
                "AM22", "Bob", new BigDecimal("12.30"), "AMD");

        consumer.onTransferCompleted(json.writeValueAsString(message));

        verify(notifications).onTransferCompleted(message);
    }

    /** The event exactly as the ledger publishes it, plus a future field: fields not needed here are ignored. */
    @Test
    void readsTheLedgersPublishedFormatAndIgnoresFieldsItDoesNotKnow() {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String payload = """
                {"schemaVersion":1,"transferId":"%s","type":"TRANSFER","createdAt":"2026-01-02T03:04:05.123Z",
                 "sourceAccountId":"aaaaaaaa-0000-0000-0000-000000000001","sourceIban":"AM11","sourceOwner":"Anna",
                 "targetAccountId":"bbbbbbbb-0000-0000-0000-000000000002","targetIban":"AM22","targetOwner":"Bob",
                 "amount":12.30,"currency":"AMD","someFutureField":[1,2,3]}""".formatted(id);

        consumer.onTransferCompleted(payload);

        verify(notifications).onTransferCompleted(new TransferCompletedMessage(id, "TRANSFER",
                "AM11", "Anna", "AM22", "Bob", new BigDecimal("12.30"), "AMD"));
    }

    @Test
    void legacyMessageWithoutDetailsIsRejectedForTheDeadLetterTopic() {
        assertThatThrownBy(() -> consumer.onTransferCompleted("{\"transferId\":\"" + UUID.randomUUID() + "\"}"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(notifications);
    }

    @Test
    void malformedPayloadAndMissingIdAreRejected() {
        assertThatThrownBy(() -> consumer.onTransferCompleted("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.onTransferCompleted("{}")).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(notifications);
    }
}
