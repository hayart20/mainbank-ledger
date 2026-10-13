package com.mainbank.ledger.dto;

import com.mainbank.ledger.domain.entity.Account;
import com.mainbank.ledger.domain.constant.AccountStatus;
import com.mainbank.ledger.domain.constant.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, String iban, String ownerName, Currency currency, BigDecimal balance,
                              BigDecimal overdraftLimit, AccountStatus status, Instant updatedAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getIban(), a.getOwnerName(), a.getCurrency(), a.getBalance(),
                a.getOverdraftLimit(), a.getStatus(), a.getUpdatedAt());
    }
}
