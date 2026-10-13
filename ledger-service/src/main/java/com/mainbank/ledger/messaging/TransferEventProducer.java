package com.mainbank.ledger.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.ledger.events.TransferCompletedMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announces a completed transfer to other services (receipts, notifications, ...) without the transfer code
 * knowing who listens.
 * <p>
 * This is a <em>transactional outbox</em>: the event is written to the {@code outbox_events} table inside the same
 * database transaction as the money movement, so the event exists if and only if the transfer committed. It is
 * published to Kafka afterwards by {@link OutboxRelay}, at least once. A broker outage or a crash right after the
 * commit therefore delays an event but can never lose it, and a rolled-back transfer never announces anything.
 */
@Component
public class TransferEventProducer {

    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final String topic;

    public TransferEventProducer(OutboxRepository outbox, ObjectMapper json,
                                 @Value("${ledger.kafka.transfer-completed-topic}") String topic) {
        this.outbox = outbox;
        this.json = json;
        this.topic = topic;
    }

    /** Must run inside the transfer's transaction; the Kafka key is the transfer id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(TransferCompletedMessage message) {
        try {
            outbox.save(OutboxEvent.of(topic, message.transferId(), json.writeValueAsString(message)));
        } catch (JsonProcessingException e) {
            // a programming error: failing here rolls the transfer back instead of silently losing the event
            throw new IllegalStateException("Cannot serialise transfer-completed event " + message.transferId(), e);
        }
    }
}
