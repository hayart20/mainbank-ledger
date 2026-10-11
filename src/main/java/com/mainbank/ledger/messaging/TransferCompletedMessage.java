package com.mainbank.ledger.messaging;

import java.util.UUID;

/** Kafka payload (JSON) announcing that a transfer was committed and its receipt can be generated. */
public record TransferCompletedMessage(UUID transferId) {
}
