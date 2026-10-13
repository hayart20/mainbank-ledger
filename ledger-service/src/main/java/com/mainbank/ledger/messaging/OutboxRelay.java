package com.mainbank.ledger.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes the outbox to Kafka, oldest first, and marks each row once the broker acknowledged it.
 * <p>
 * Delivery is at-least-once: a crash between the acknowledgement and the commit publishes the row again, which is
 * why consumers must be idempotent (the receipt service is). On the first failure the batch stops and the next
 * run continues from the same row, so per-transfer ordering is preserved and a dead broker only delays events.
 * Several instances can run this concurrently: rows are claimed with {@code skip locked}.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Duration retention;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       @Value("${ledger.outbox.retention:7d}") Duration retention) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval:500ms}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outbox.lockBatch(BATCH_SIZE);
        for (OutboxEvent event : batch) {
            try {
                kafka.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.markPublished();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("Could not publish outbox event {} for {}, will retry: {}", event.getId(), event.getAggregateId(), e.toString());
                return; // keep what was already published in this batch; resume from this row next run
            }
        }
    }

    /** Published rows are kept for a while for diagnostics, then removed. */
    @Scheduled(fixedDelayString = "${ledger.outbox.cleanup-interval:1h}", initialDelayString = "${ledger.outbox.cleanup-interval:1h}")
    @Transactional
    public void deleteOldPublished() {
        int deleted = outbox.deletePublishedBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Removed {} published outbox events older than {}", deleted, retention);
        }
    }
}
