package com.mainbank.ledger.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.ledger.service.receipt.ReceiptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TransferCompletedConsumerTest {

    @Mock ReceiptService receipts;
    @Spy ObjectMapper json = new ObjectMapper();
    @InjectMocks TransferCompletedConsumer consumer;

    @Test
    void generatesTheReceiptForTheAnnouncedTransfer() {
        UUID id = UUID.randomUUID();

        consumer.onTransferCompleted("{\"transferId\":\"" + id + "\"}");

        verify(receipts).generateAndStore(id);
    }

    @Test
    void malformedPayloadIsRejectedWithoutTouchingReceipts() {
        assertThatThrownBy(() -> consumer.onTransferCompleted("not json"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(receipts);
    }

    @Test
    void payloadWithoutTransferIdIsRejected() {
        assertThatThrownBy(() -> consumer.onTransferCompleted("{}"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(receipts);
    }
}
