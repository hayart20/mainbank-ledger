package com.mainbank.ledger.dto;

import com.mainbank.ledger.domain.constant.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @Schema(description = "Account holder name", example = "Anna Hakobyan", maxLength = 200)
        @NotBlank @Size(max = 200) String ownerName,
        @Schema(description = "Account currency; cannot be changed later", example = "USD")
        @NotNull Currency currency,
        @Schema(description = "Opening balance, funded from the bank's settlement account. Defaults to 0.",
                example = "1000.00", minimum = "0")
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal initialBalance,
        @Schema(description = "How far below zero the balance may go. Defaults to 0 (no overdraft).",
                example = "0.00", minimum = "0")
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal overdraftLimit) {

    public BigDecimal initialBalanceOrZero() {
        return initialBalance == null ? BigDecimal.ZERO : initialBalance;
    }

    public BigDecimal overdraftLimitOrZero() {
        return overdraftLimit == null ? BigDecimal.ZERO : overdraftLimit;
    }
}
