package com.mainbank.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.ledger.events.TransferCompletedMessage;
import com.mainbank.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code transfer-completed} events and notifies the account holders. It has its own consumer group, so it
 * receives every event independently of the receipt service. Only a message that can never succeed is rejected, and
 * then dead-lettered (see {@code KafkaConfig}).
 */
@Component
public class TransferCompletedConsumer {

    private final NotificationService notifications;
    private final ObjectMapper json;

    public TransferCompletedConsumer(NotificationService notifications, ObjectMapper json) {
        this.notifications = notifications;
        this.json = json;
    }

    @KafkaListener(topics = "${notification.kafka.topic}")
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
            // legacy message published before the event carried details: nobody can be told anything useful
            throw new IllegalArgumentException("transfer-completed message without details: " + message.transferId());
        }
        notifications.onTransferCompleted(message);
    }
}
