package com.mainbank.ledger.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka payload (JSON) announcing that a transfer was committed. It is self-contained: a consumer has everything it
 * needs to produce a receipt, a notification or a statement line without reading the ledger database.
 * <p>
 * This is the contract between the ledger and every consumer, so it only uses plain types (no ledger domain classes);
 * {@code type} and {@code currency} are the enum names. Add fields, never repurpose them, and bump
 * {@link #SCHEMA_VERSION} when the meaning changes. Messages published before schema version 1 carry only
 * {@code transferId}, see {@link #hasDetails()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompletedMessage(int schemaVersion, UUID transferId, String type, Instant createdAt,
                                       UUID sourceAccountId, String sourceIban, String sourceOwner,
                                       UUID targetAccountId, String targetIban, String targetOwner,
                                       BigDecimal amount, String currency) {

    public static final int SCHEMA_VERSION = 1;

    /** False for legacy messages that only carry the transfer id; consumers then have to look the transfer up. */
    public boolean hasDetails() {
        return type != null && createdAt != null && sourceIban != null && sourceOwner != null
                && targetIban != null && targetOwner != null && amount != null && currency != null;
    }
}
