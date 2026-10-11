package com.mainbank.ledger.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.ledger.service.receipt.ReceiptService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Generates the receipt for every announced transfer. Redelivery is harmless: {@link ReceiptService#generateAndStore}
 * is idempotent and records its own failures, so only a malformed message is ever rejected (and then dead-lettered,
 * see {@code KafkaConfig}).
 */
@Component
public class TransferCompletedConsumer {

    private final ReceiptService receipts;
    private final ObjectMapper json;

    public TransferCompletedConsumer(ReceiptService receipts, ObjectMapper json) {
        this.receipts = receipts;
        this.json = json;
    }

    @KafkaListener(topics = "${ledger.kafka.transfer-completed-topic}")
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
        receipts.generateAndStore(message.transferId());
    }
}
