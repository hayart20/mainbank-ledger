package com.mainbank.ledger.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String TOPIC = "ledger.transfer.completed";

    @Mock OutboxRepository outbox;
    @Mock KafkaTemplate<String, String> kafka;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outbox, kafka, Duration.ofDays(7));
    }

    private static OutboxEvent event(UUID id, String payload) {
        return OutboxEvent.of(TOPIC, id, payload);
    }

    @Test
    void publishesInOrderKeyedByAggregateIdAndMarksEachEventPublished() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        OutboxEvent e1 = event(id1, "{\"n\":1}");
        OutboxEvent e2 = event(id2, "{\"n\":2}");
        when(outbox.lockBatch(anyInt())).thenReturn(List.of(e1, e2));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPending();

        verify(kafka).send(TOPIC, id1.toString(), "{\"n\":1}");
        verify(kafka).send(TOPIC, id2.toString(), "{\"n\":2}");
        assertThat(e1.getPublishedAt()).isNotNull();
        assertThat(e2.getPublishedAt()).isNotNull();
    }

    @Test
    void stopsAtTheFirstFailureAndLeavesTheRestUnpublishedForTheNextRun() {
        OutboxEvent first = event(UUID.randomUUID(), "{\"n\":1}");
        OutboxEvent second = event(UUID.randomUUID(), "{\"n\":2}");
        when(outbox.lockBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafka.send(eq(TOPIC), eq(first.getAggregateId().toString()), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.publishPending();

        assertThat(first.getPublishedAt()).isNull();
        assertThat(second.getPublishedAt()).isNull();
        verify(kafka, never()).send(eq(TOPIC), eq(second.getAggregateId().toString()), anyString());
    }

    @Test
    void synchronousSendErrorDoesNotEscapeTheScheduler() {
        OutboxEvent e = event(UUID.randomUUID(), "{}");
        when(outbox.lockBatch(anyInt())).thenReturn(List.of(e));
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("not connected"));

        relay.publishPending();

        assertThat(e.getPublishedAt()).isNull();
    }

    @Test
    void cleanupRemovesOnlyOldPublishedRows() {
        when(outbox.deletePublishedBefore(org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(3);

        relay.deleteOldPublished();

        org.mockito.ArgumentCaptor<Instant> before = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(outbox).deletePublishedBefore(before.capture());
        assertThat(Duration.between(before.getValue(), Instant.now())).isBetween(Duration.ofDays(7), Duration.ofDays(7).plusMinutes(1));
    }
}
