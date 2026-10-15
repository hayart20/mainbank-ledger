package com.mainbank.receipt.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.receipt.service.ReceiptService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code transfer-completed} events from the ledger and produces the receipt.
 * <p>
 * Redelivery is harmless because {@link ReceiptService} is idempotent and records its own failures; only a
 * message that can never succeed is rejected, and then dead-lettered (see {@code KafkaConfig}).
 */
@Component
public class TransferCompletedConsumer {

    private final ReceiptService receipts;
    private final ObjectMapper json;

    public TransferCompletedConsumer(ReceiptService receipts, ObjectMapper json) {
        this.receipts = receipts;
        this.json = json;
    }

    @KafkaListener(topics = "${receipt.kafka.topic}")
    public void onTransferCompleted(String payload) {
        TransferCompletedMessage message;
        try {
            message = json.readValue(payload, TransferCompletedMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed transfer-completed message: " + payload, e);
        }
        if (message.transferId() == null) {
            throw new IllegalArgumentException("transfer-completed message without transferId: " + payload);
        }
        if (!message.hasDetails()) {
            // the receipt cannot be reconstructed from the id alone: this service has no ledger access
            throw new IllegalArgumentException("transfer-completed message without details: " + message.transferId());
        }
        receipts.onTransferCompleted(message);
    }
}
