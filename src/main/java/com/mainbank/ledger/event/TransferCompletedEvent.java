package com.mainbank.ledger.event;

import java.util.UUID;

/** Published inside the transfer transaction; consumed only AFTER_COMMIT so rolled-back transfers never get receipts. */
public record TransferCompletedEvent(UUID transferId) {
}
