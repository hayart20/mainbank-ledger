-- Transactional outbox: a completed transferEntity and the event announcing it are committed atomically; a relay
-- publishes the rows to Kafka afterwards (at-least-once), so a broker outage can never lose an event.
CREATE TABLE outbox_events (
    id           BIGSERIAL PRIMARY KEY,
    aggregate_id UUID         NOT NULL,       -- Kafka message key (the transferEntity id)
    topic        VARCHAR(200) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- the relay only ever reads the unpublished rows, oldest first
CREATE INDEX ix_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
