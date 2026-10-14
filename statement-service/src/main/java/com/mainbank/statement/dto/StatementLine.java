package com.mainbank.statement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One movement on an account.
 *
 * @param entryType {@code DEBIT} (money left the account) or {@code CREDIT} (money arrived)
 */
public record StatementLine(
        @Schema(description = "Transfer that caused this movement") UUID transferId,
        @Schema(description = "When the transfer was committed (UTC)") Instant occurredAt,
        @Schema(description = "DEBIT: money left the account; CREDIT: money arrived", allowableValues = {"DEBIT", "CREDIT"})
        String entryType,
        @Schema(description = "TRANSFER or OPENING_DEPOSIT", example = "TRANSFER") String transferType,
        @Schema(example = "250.00") BigDecimal amount,
        @Schema(example = "USD") String currency,
        @Schema(description = "The other account of the transfer") UUID counterpartyAccountId,
        String counterpartyIban,
        String counterpartyOwner) {
}
