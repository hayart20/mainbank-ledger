package com.mainbank.ledger.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One event waiting to be (or already) published to Kafka. Written in the same transaction as the transfer. */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(nullable = false, updatable = false)
    private String topic;

    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() { }

    public static OutboxEvent of(String topic, UUID aggregateId, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.topic = topic;
        e.aggregateId = aggregateId;
        e.payload = payload;
        e.createdAt = Instant.now();
        return e;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public Long getId() { return id; }

    public UUID getAggregateId() { return aggregateId; }

    public String getTopic() { return topic; }

    public String getPayload() { return payload; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getPublishedAt() { return publishedAt; }
}
