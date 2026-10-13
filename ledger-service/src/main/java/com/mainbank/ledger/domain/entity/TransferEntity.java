package com.mainbank.ledger.domain.entity;

import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.TransferStatus;
import com.mainbank.ledger.domain.constant.TransferType;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class TransferEntity
{

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransferType type;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false, updatable = false)
    private UUID targetAccountId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferEntity() { }

    public UUID getId() { return id; }

    public UUID getIdempotencyKey() { return idempotencyKey; }

    public TransferType getType() { return type; }

    public UUID getSourceAccountId() { return sourceAccountId; }

    public UUID getTargetAccountId() { return targetAccountId; }

    public BigDecimal getAmount() { return amount; }

    public Currency getCurrency() { return currency; }

    public TransferStatus getStatus() { return status; }

    public Instant getCreatedAt() { return createdAt; }

    /**
     * Creates the record of a transfer whose money movement has just been executed.
     * <p>
     * It is needed because a transfer must always start in a consistent state: a new random id, status
     * {@link TransferStatus#COMPLETED} and a creation timestamp. A static factory with the name {@code completed}
     * keeps those defaults in one place and makes clear that the object is only built after the accounts were
     * debited and credited inside the same transaction. A failed transfer is never stored: the transaction rolls
     * back, so there is no "failed" transfer row to create. The entity has no setters and no update path, so a
     * transfer never changes after it is created.
     *
     * @param idempotencyKey the client's {@code X-Idempotency-Key}; for internal transfers a random UUID
     * @param type           {@link TransferType#TRANSFER} or {@link TransferType#OPENING_DEPOSIT}
     * @param source         id of the debited account
     * @param target         id of the credited account
     * @param amount         positive amount scaled to 2 decimals
     * @param currency       currency shared by both accounts
     */
    public static TransferEntity completed(UUID idempotencyKey, TransferType type, UUID source, UUID target,
                                           BigDecimal amount, Currency currency) {
        TransferEntity t = new TransferEntity();
        t.id = UUID.randomUUID();
        t.idempotencyKey = idempotencyKey;
        t.type = type;
        t.sourceAccountId = source;
        t.targetAccountId = target;
        t.amount = amount;
        t.currency = currency;
        t.status = TransferStatus.COMPLETED;
        // Millisecond precision: the receipt hash uses epoch millis and must be identical whether the timestamp
        // comes from this object (the Kafka event) or is read back from the database, which rounds to microseconds.
        t.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return t;
    }
}
