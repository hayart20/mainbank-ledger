package com.mainbank.receipt.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.ledger.events.TransferCompletedMessage;
import com.mainbank.receipt.service.ReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferCompletedConsumerTest {

    @Mock ReceiptService receipts;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private TransferCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TransferCompletedConsumer(receipts, json);
    }

    @Test
    void selfContainedMessageIsHandedToTheReceiptService() throws Exception {
        TransferCompletedMessage message = new TransferCompletedMessage(1, UUID.randomUUID(), "TRANSFER",
                Instant.parse("2026-01-02T03:04:05.123Z"), UUID.randomUUID(), "AM11", "Anna",
                UUID.randomUUID(), "AM22", "Bob", new BigDecimal("12.30"), "AMD");

        consumer.onTransferCompleted(json.writeValueAsString(message));

        verify(receipts).onTransferCompleted(message);
        verifyNoMoreInteractions(receipts);
    }

    @Test
    void legacyMessageForAnImportedReceiptIsAcknowledged() {
        UUID id = UUID.randomUUID();
        when(receipts.exists(id)).thenReturn(true);

        assertThatCode(() -> consumer.onTransferCompleted("{\"transferId\":\"" + id + "\"}")).doesNotThrowAnyException();

        verify(receipts).exists(id);
        verifyNoMoreInteractions(receipts);
    }

    @Test
    void legacyMessageWithoutAnImportedReceiptIsRejectedForTheDeadLetterTopic() {
        UUID id = UUID.randomUUID();
        when(receipts.exists(id)).thenReturn(false);

        assertThatThrownBy(() -> consumer.onTransferCompleted("{\"transferId\":\"" + id + "\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedPayloadIsRejected() {
        assertThatThrownBy(() -> consumer.onTransferCompleted("not json"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoMoreInteractions(receipts);
    }

    @Test
    void payloadWithoutTransferIdIsRejected() {
        assertThatThrownBy(() -> consumer.onTransferCompleted("{}"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoMoreInteractions(receipts);
    }
}
