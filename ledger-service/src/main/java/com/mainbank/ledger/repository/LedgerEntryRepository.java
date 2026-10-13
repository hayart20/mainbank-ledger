package com.mainbank.ledger.repository;

import com.mainbank.ledger.domain.entity.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, Long> {
    List<LedgerEntryEntity> findByTransferIdOrderByIdAsc(UUID transferId);
}
