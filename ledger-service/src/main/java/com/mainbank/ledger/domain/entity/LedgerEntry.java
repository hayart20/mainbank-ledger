package com.mainbank.ledger.domain.entity;

import com.mainbank.ledger.domain.constant.EntryType;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable posting leg. Rows are append-only: all columns are non-updatable (edits are never flushed) and removal is rejected. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private EntryType entryType;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() { }

    public LedgerEntry(UUID transferId, UUID accountId, EntryType entryType, BigDecimal amount) {
        this.transferId = transferId;
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }

    public UUID getTransferId() { return transferId; }

    public UUID getAccountId() { return accountId; }

    public EntryType getEntryType() { return entryType; }

    public BigDecimal getAmount() { return amount; }

    public Instant getCreatedAt() { return createdAt; }

    /**
     * JPA lifecycle callback invoked before Hibernate writes an UPDATE for this entry; it always throws, so a
     * ledger row can never be modified through the entity.
     * <p>
     * It is a safety net: all columns are {@code updatable = false}, so Hibernate normally detects nothing to
     * update and silently ignores edits to a loaded entry, which means this callback is not expected to fire.
     * It protects against someone later making a column updatable. Bulk JPQL/SQL statements bypass lifecycle
     * callbacks entirely.
     *
     * @throws IllegalStateException always
     */
    @PreUpdate
    void rejectUpdate() {
        throw new IllegalStateException("Ledger entries are append-only: update of entry " + id + " rejected");
    }

    /**
     * JPA lifecycle callback invoked before this entry is deleted (for example via {@code repository.delete}
     * or {@code EntityManager.remove}); it always throws, so ledger history cannot be erased through the entity.
     * Corrections must be posted as new, opposite entries instead. Like {@link #rejectUpdate()}, it does not
     * apply to bulk JPQL/SQL deletes.
     *
     * @throws IllegalStateException always
     */
    @PreRemove
    void rejectRemove() {
        throw new IllegalStateException("Ledger entries are append-only: delete of entry " + id + " rejected");
    }
}
