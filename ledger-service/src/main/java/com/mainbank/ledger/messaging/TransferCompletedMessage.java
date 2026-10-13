package com.mainbank.ledger.messaging;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka payload (JSON) announcing that a transfer was committed: the published contract of topic
 * {@code ledger.transfer.completed}. The ledger owns the format; every consumer keeps its own copy and reads only
 * the fields it needs, ignoring the rest, so services are built and deployed independently.
 * <p>
 * It is self-contained (consumers never read ledger tables) and uses plain types; {@code type} and
 * {@code currency} are the enum names. Compatibility rules: add fields, never rename, repurpose or remove one,
 * and bump {@link #SCHEMA_VERSION} only for a change that cannot be expressed as an addition.
 */
public record TransferCompletedMessage(int schemaVersion, UUID transferId, String type,
                                       // ISO-8601 text on the wire whatever the ObjectMapper is configured to do
                                       @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
                                       UUID sourceAccountId, String sourceIban, String sourceOwner,
                                       UUID targetAccountId, String targetIban, String targetOwner,
                                       BigDecimal amount, String currency) {

    public static final int SCHEMA_VERSION = 1;
}
