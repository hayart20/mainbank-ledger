package com.mainbank.ledger.dto;

import com.mainbank.ledger.domain.constant.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank @Size(max = 200) String ownerName,
        @NotNull Currency currency,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal initialBalance,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal overdraftLimit) {

    public BigDecimal initialBalanceOrZero() {
        return initialBalance == null ? BigDecimal.ZERO : initialBalance;
    }

    public BigDecimal overdraftLimitOrZero() {
        return overdraftLimit == null ? BigDecimal.ZERO : overdraftLimit;
    }
}
