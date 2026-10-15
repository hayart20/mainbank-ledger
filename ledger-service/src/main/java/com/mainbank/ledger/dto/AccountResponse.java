package com.mainbank.ledger.dto;

import com.mainbank.ledger.domain.entity.AccountEntity;
import com.mainbank.ledger.domain.constant.AccountStatus;
import com.mainbank.ledger.domain.constant.Currency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        @Schema(description = "Account id") UUID id,
        @Schema(description = "Generated IBAN", example = "AM00MAINBANK1000000001") String iban,
        @Schema(description = "Account holder name") String ownerName,
        Currency currency,
        @Schema(description = "Current balance; may be negative down to -overdraftLimit", example = "1000.00")
        BigDecimal balance,
        @Schema(description = "How far below zero the balance may go", example = "0.00") BigDecimal overdraftLimit,
        @Schema(description = "ACTIVE accounts can send and receive; FROZEN ones cannot") AccountStatus status,
        @Schema(description = "Last time the account row changed (UTC)") Instant updatedAt) {

    public static AccountResponse from(AccountEntity a) {
        return new AccountResponse(a.getId(), a.getIban(), a.getOwnerName(), a.getCurrency(), a.getBalance(),
                a.getOverdraftLimit(), a.getStatus(), a.getUpdatedAt());
    }
}
