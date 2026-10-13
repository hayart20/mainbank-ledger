package com.mainbank.receipt.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One receipt per transfer. It stores the facts it attests to (copied from the transfer-completed event), so the
 * receipt service never needs the ledger database to build or rebuild the PDF. Rows are created and updated only
 * through {@code ReceiptRepository}.
 */
@Entity
@Table(name = "receipts")
public class Receipt {

    @Id
    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(name = "transfer_created_at", nullable = false, updatable = false)
    private Instant transferCreatedAt;

    @Column(name = "source_iban", nullable = false, updatable = false)
    private String sourceIban;

    @Column(name = "source_owner", nullable = false, updatable = false)
    private String sourceOwner;

    @Column(name = "target_iban", nullable = false, updatable = false)
    private String targetIban;

    @Column(name = "target_owner", nullable = false, updatable = false)
    private String targetOwner;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReceiptStatus status;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "details_sha256")
    private String detailsSha256;

    @Column(name = "pdf_sha256")
    private String pdfSha256;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Receipt() { }

    public UUID getTransferId() { return transferId; }

    public String getType() { return type; }

    public Instant getTransferCreatedAt() { return transferCreatedAt; }

    public String getSourceIban() { return sourceIban; }

    public String getSourceOwner() { return sourceOwner; }

    public String getTargetIban() { return targetIban; }

    public String getTargetOwner() { return targetOwner; }

    public BigDecimal getAmount() { return amount; }

    public String getCurrency() { return currency; }

    public ReceiptStatus getStatus() { return status; }

    public String getObjectKey() { return objectKey; }

    public String getDetailsSha256() { return detailsSha256; }

    public String getPdfSha256() { return pdfSha256; }

    public int getAttempts() { return attempts; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
