package com.mainbank.ledger.repository;

import com.mainbank.ledger.domain.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByTransferIdOrderByIdAsc(UUID transferId);
}
