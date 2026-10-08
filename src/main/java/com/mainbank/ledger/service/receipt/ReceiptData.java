package com.mainbank.ledger.service.receipt;

import com.mainbank.ledger.util.Hashing;

import com.mainbank.ledger.domain.entity.Account;
import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.entity.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReceiptData(UUID transferId, Instant createdAt, String sourceIban, String sourceOwner,
                          String targetIban, String targetOwner, BigDecimal amount, Currency currency, String type) {

    public static ReceiptData of(Transfer t, Account source, Account target) {
        return new ReceiptData(t.getId(), t.getCreatedAt(), source.getIban(), source.getOwnerName(),
                target.getIban(), target.getOwnerName(), t.getAmount(), t.getCurrency(), t.getType().name());
    }

    /** Stable, unambiguous serialisation of the facts a receipt attests to. */
    public String canonical() {
        return String.join("|", transferId.toString(), String.valueOf(createdAt.toEpochMilli()), sourceIban, targetIban,
                amount.toPlainString(), currency.name(), type);
    }

    /** SHA-256 over {@link #canonical()}; printed on the PDF so it can be re-verified against the database. */
    public String detailsHash() {
        return Hashing.sha256Hex(canonical());
    }
}
