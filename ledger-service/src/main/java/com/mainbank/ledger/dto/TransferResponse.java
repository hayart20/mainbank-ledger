package com.mainbank.ledger.dto;

import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.EntryType;
import com.mainbank.ledger.domain.entity.LedgerEntryEntity;
import com.mainbank.ledger.domain.entity.TransferEntity;
import com.mainbank.ledger.domain.constant.TransferStatus;
import com.mainbank.ledger.domain.constant.TransferType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferResponse(
        @Schema(description = "Transfer id; also the id of its receipt (`/api/v1/receipts/{id}`)") UUID id,
        @Schema(description = "TRANSFER for customer payments, OPENING_DEPOSIT for an account's initial funding")
        TransferType type,
        TransferStatus status,
        UUID sourceAccountId,
        UUID targetAccountId,
        @Schema(example = "250.00") BigDecimal amount,
        Currency currency,
        @Schema(description = "When the transfer was committed (UTC)") Instant createdAt,
        @Schema(description = "The double-entry legs: one DEBIT on the source and one CREDIT on the target")
        List<Leg> ledgerEntries) {

    public record Leg(Long id, UUID accountId,
                      @Schema(description = "DEBIT: money left the account; CREDIT: money arrived") EntryType entryType,
                      @Schema(example = "250.00") BigDecimal amount, Instant createdAt) {
        static Leg from(LedgerEntryEntity e) {
            return new Leg(e.getId(), e.getAccountId(), e.getEntryType(), e.getAmount(), e.getCreatedAt());
        }
    }

    public static TransferResponse from(TransferEntity t, List<LedgerEntryEntity> entries) {
        return new TransferResponse(t.getId(), t.getType(), t.getStatus(), t.getSourceAccountId(),
                t.getTargetAccountId(), t.getAmount(), t.getCurrency(), t.getCreatedAt(),
                entries.stream().map(Leg::from).toList());
    }
}
