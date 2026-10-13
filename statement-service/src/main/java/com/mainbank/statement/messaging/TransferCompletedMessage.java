package com.mainbank.statement.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * This service's view of the {@code ledger.transfer.completed} event: what a statement line needs. The ledger owns
 * the format; unknown fields (such as {@code schemaVersion} or future additions) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompletedMessage(UUID transferId, String type, Instant createdAt,
                                       UUID sourceAccountId, String sourceIban, String sourceOwner,
                                       UUID targetAccountId, String targetIban, String targetOwner,
                                       BigDecimal amount, String currency) {

    /** False for legacy messages that only carry the transfer id. */
    public boolean hasDetails() {
        return type != null && createdAt != null && sourceAccountId != null && sourceIban != null
                && sourceOwner != null && targetAccountId != null && targetIban != null && targetOwner != null
                && amount != null && currency != null;
    }
}
