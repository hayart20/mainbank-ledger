package com.mainbank.notification.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * This service's view of the {@code ledger.transfer.completed} event: only what a notice needs. The ledger owns
 * the format; unknown fields (account ids, timestamps, future additions) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompletedMessage(UUID transferId, String type,
                                       String sourceIban, String sourceOwner,
                                       String targetIban, String targetOwner,
                                       BigDecimal amount, String currency) {

    /** False for legacy messages that only carry the transfer id. */
    public boolean hasDetails() {
        return type != null && sourceIban != null && sourceOwner != null
                && targetIban != null && targetOwner != null && amount != null && currency != null;
    }
}
