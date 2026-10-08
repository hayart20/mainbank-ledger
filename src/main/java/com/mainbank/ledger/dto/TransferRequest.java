package com.mainbank.ledger.dto;

import com.mainbank.ledger.domain.constant.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID targetAccountId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull Currency currency) {

    /** Canonical form used to detect an idempotency key being re-used with a different payload. */
    public String canonical() {
        return sourceAccountId + "|" + targetAccountId + "|" + amount.setScale(2).toPlainString() + "|" + currency;
    }
}
