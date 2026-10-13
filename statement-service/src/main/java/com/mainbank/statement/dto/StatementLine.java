package com.mainbank.statement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One movement on an account.
 *
 * @param entryType {@code DEBIT} (money left the account) or {@code CREDIT} (money arrived)
 */
public record StatementLine(UUID transferId, Instant occurredAt, String entryType, String transferType,
                            BigDecimal amount, String currency,
                            UUID counterpartyAccountId, String counterpartyIban, String counterpartyOwner) {
}
