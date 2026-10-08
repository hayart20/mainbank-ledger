package com.mainbank.ledger.repository;

import com.mainbank.ledger.domain.constant.ReceiptStatus;
import com.mainbank.ledger.domain.entity.Transfer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    @Transactional
    @Modifying
    @Query("""
            update Transfer t set t.receiptStatus = com.mainbank.ledger.domain.constant.ReceiptStatus.READY,
                   t.receiptObjectKey = :key, t.receiptSha256 = :detailsHash, t.receiptPdfSha256 = :pdfHash,
                   t.receiptAttempts = t.receiptAttempts + 1
             where t.id = :id""")
    int markReceiptReady(@Param("id") UUID id, @Param("key") String key,
                         @Param("detailsHash") String detailsHash, @Param("pdfHash") String pdfHash);

    @Transactional
    @Modifying
    @Query("""
            update Transfer t set t.receiptStatus = com.mainbank.ledger.domain.constant.ReceiptStatus.FAILED,
                   t.receiptAttempts = t.receiptAttempts + 1
             where t.id = :id and t.receiptStatus <> com.mainbank.ledger.domain.constant.ReceiptStatus.READY""")
    int markReceiptFailed(@Param("id") UUID id);

    @Query("""
            select t.id from Transfer t
             where t.receiptStatus <> com.mainbank.ledger.domain.constant.ReceiptStatus.READY
               and t.receiptAttempts < :maxAttempts and t.createdAt < :olderThan
             order by t.createdAt""")
    List<UUID> findReceiptsToRetry(@Param("maxAttempts") int maxAttempts,
                                   @Param("olderThan") Instant olderThan, Pageable page);

    long countByReceiptStatus(ReceiptStatus status);
}
