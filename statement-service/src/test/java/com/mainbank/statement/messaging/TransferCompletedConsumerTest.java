package com.mainbank.statement.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.ledger.events.TransferCompletedMessage;
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
        TransferCompletedMessage message = new TransferCompletedMessage(1, UUID.randomUUID(), "TRANSFER",
                Instant.parse("2026-01-02T03:04:05.123Z"), UUID.randomUUID(), "AM11", "Anna",
                UUID.randomUUID(), "AM22", "Bob", new BigDecimal("12.30"), "AMD");

        consumer.onTransferCompleted(json.writeValueAsString(message));

        verify(statements).onTransferCompleted(message);
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
