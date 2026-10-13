package com.mainbank.ledger.dto;

import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.EntryType;
import com.mainbank.ledger.domain.entity.LedgerEntry;
import com.mainbank.ledger.domain.entity.Transfer;
import com.mainbank.ledger.domain.constant.TransferStatus;
import com.mainbank.ledger.domain.constant.TransferType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferResponse(UUID id, TransferType type, TransferStatus status, UUID sourceAccountId,
                               UUID targetAccountId, BigDecimal amount, Currency currency, Instant createdAt,
                               List<Leg> ledgerEntries) {

    public record Leg(Long id, UUID accountId, EntryType entryType, BigDecimal amount, Instant createdAt) {
        static Leg from(LedgerEntry e) {
            return new Leg(e.getId(), e.getAccountId(), e.getEntryType(), e.getAmount(), e.getCreatedAt());
        }
    }

    public static TransferResponse from(Transfer t, List<LedgerEntry> entries) {
        return new TransferResponse(t.getId(), t.getType(), t.getStatus(), t.getSourceAccountId(),
                t.getTargetAccountId(), t.getAmount(), t.getCurrency(), t.getCreatedAt(),
                entries.stream().map(Leg::from).toList());
    }
}
