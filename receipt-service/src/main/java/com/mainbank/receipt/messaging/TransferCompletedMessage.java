package com.mainbank.receipt.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * This service's view of the {@code ledger.transfer.completed} event: only the fields a receipt needs. The ledger
 * owns the format; unknown fields are ignored, so the ledger can add fields without any change here.
 * <p>
 * Messages published before the event carried details contain only {@code transferId}, see {@link #hasDetails()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompletedMessage(UUID transferId, String type, Instant createdAt,
                                       String sourceIban, String sourceOwner,
                                       String targetIban, String targetOwner,
                                       BigDecimal amount, String currency) {

    /** False for legacy messages that only carry the transfer id. */
    public boolean hasDetails() {
        return type != null && createdAt != null && sourceIban != null && sourceOwner != null
                && targetIban != null && targetOwner != null && amount != null && currency != null;
    }
}
