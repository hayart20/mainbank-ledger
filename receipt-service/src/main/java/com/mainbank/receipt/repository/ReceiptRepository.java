package com.mainbank.receipt.repository;

import com.mainbank.receipt.domain.Receipt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    /**
     * Idempotent create: a redelivered Kafka message finds the row and changes nothing.
     *
     * @return 1 if the row was created, 0 if the receipt already existed
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into receipts (transfer_id, type, transfer_created_at, source_iban, source_owner,
                                  target_iban, target_owner, amount, currency)
            values (:id, :type, :transferCreatedAt, :sourceIban, :sourceOwner, :targetIban, :targetOwner, :amount, :currency)
            on conflict (transfer_id) do nothing""")
    int insertIfAbsent(@Param("id") UUID id, @Param("type") String type,
                       @Param("transferCreatedAt") Instant transferCreatedAt,
                       @Param("sourceIban") String sourceIban, @Param("sourceOwner") String sourceOwner,
                       @Param("targetIban") String targetIban, @Param("targetOwner") String targetOwner,
                       @Param("amount") BigDecimal amount, @Param("currency") String currency);

    @Transactional
    @Modifying
    @Query("""
            update Receipt r set r.status = com.mainbank.receipt.domain.ReceiptStatus.READY,
                   r.objectKey = :key, r.detailsSha256 = :detailsHash, r.pdfSha256 = :pdfHash,
                   r.attempts = r.attempts + 1, r.updatedAt = :now
             where r.transferId = :id""")
    int markReady(@Param("id") UUID id, @Param("key") String key, @Param("detailsHash") String detailsHash,
                  @Param("pdfHash") String pdfHash, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("""
            update Receipt r set r.status = com.mainbank.receipt.domain.ReceiptStatus.FAILED,
                   r.attempts = r.attempts + 1, r.updatedAt = :now
             where r.transferId = :id and r.status <> com.mainbank.receipt.domain.ReceiptStatus.READY""")
    int markFailed(@Param("id") UUID id, @Param("now") Instant now);

    @Query("""
            select r from Receipt r
             where r.status <> com.mainbank.receipt.domain.ReceiptStatus.READY
               and r.attempts < :maxAttempts and r.updatedAt < :olderThan
             order by r.updatedAt""")
    List<Receipt> findRetryable(@Param("maxAttempts") int maxAttempts, @Param("olderThan") Instant olderThan,
                                Pageable page);

    /**
     * Optimistic claim for the retry job: succeeds only if nobody touched the row since it was read, so several
     * receipt-service replicas never process the same receipt twice.
     *
     * @return 1 if this caller won the claim
     */
    @Transactional
    @Modifying
    @Query("""
            update Receipt r set r.updatedAt = :now
             where r.transferId = :id and r.updatedAt = :seen
               and r.status <> com.mainbank.receipt.domain.ReceiptStatus.READY""")
    int claim(@Param("id") UUID id, @Param("seen") Instant seen, @Param("now") Instant now);
}
