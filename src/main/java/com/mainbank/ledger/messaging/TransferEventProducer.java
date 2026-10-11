package com.mainbank.ledger.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Tells the receipt pipeline (via Kafka) that a transfer happened, without the transfer code knowing how
 * receipts are made.
 * <p>
 * The message is sent only <em>after the surrounding transaction commits</em>, so a rolled-back transfer never
 * produces a receipt and no money-row lock is held while talking to the broker. The send is best effort: a
 * failure is logged and never reaches the caller, because the transfer is already committed. A lost message is
 * recovered by {@code ReceiptService#retryPending()}, which re-processes every transfer whose receipt is not
 * {@code READY}.
 */
@Component
public class TransferEventProducer {

    private static final Logger log = LoggerFactory.getLogger(TransferEventProducer.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;

    public TransferEventProducer(KafkaTemplate<String, String> kafka, ObjectMapper json,
                                 @Value("${ledger.kafka.transfer-completed-topic}") String topic) {
        this.kafka = kafka;
        this.json = json;
        this.topic = topic;
    }

    public void publishAfterCommit(UUID transferId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(transferId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(transferId);
            }
        });
    }

    private void send(UUID transferId) {
        try {
            String payload = json.writeValueAsString(new TransferCompletedMessage(transferId));
            kafka.send(topic, transferId.toString(), payload).whenComplete((result, error) -> {
                if (error != null) {
                    log.warn("Could not publish transfer-completed for {}, the retry job will pick it up: {}",
                            transferId, error.toString());
                }
            });
        } catch (Exception e) {
            log.warn("Could not publish transfer-completed for {}, the retry job will pick it up: {}",
                    transferId, e.toString());
        }
    }
}
