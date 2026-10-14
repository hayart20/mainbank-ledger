package com.mainbank.ledger.dto;

import com.mainbank.ledger.domain.constant.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @Schema(description = "Account to debit")
        @NotNull UUID sourceAccountId,
        @Schema(description = "Account to credit; must differ from the source")
        @NotNull UUID targetAccountId,
        @Schema(description = "Amount to move, at most 2 decimals", example = "250.00", minimum = "0.01")
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Schema(description = "Must equal the currency of both accounts", example = "USD")
        @NotNull Currency currency) {

    /** Canonical form used to detect an idempotency key being re-used with a different payload. */
    public String canonical() {
        return sourceAccountId + "|" + targetAccountId + "|" + amount.setScale(2).toPlainString() + "|" + currency;
    }
}
