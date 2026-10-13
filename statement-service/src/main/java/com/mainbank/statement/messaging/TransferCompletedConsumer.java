package com.mainbank.statement.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.ledger.events.TransferCompletedMessage;
import com.mainbank.statement.service.StatementService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Builds the statement read model from {@code transfer-completed} events. It has its own consumer group, so it
 * receives every event independently of the other consumers; a new instance replays the topic from the start.
 */
@Component
public class TransferCompletedConsumer {

    private final StatementService statements;
    private final ObjectMapper json;

    public TransferCompletedConsumer(StatementService statements, ObjectMapper json) {
        this.statements = statements;
        this.json = json;
    }

    @KafkaListener(topics = "${statement.kafka.topic}")
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
        if (!message.hasDetails() || message.sourceAccountId() == null || message.targetAccountId() == null) {
            // legacy message published before the event carried details: the movement cannot be reconstructed here
            throw new IllegalArgumentException("transfer-completed message without details: " + message.transferId());
        }
        statements.onTransferCompleted(message);
    }
}
