package com.mainbank.ledger.service;

import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.entity.TransferEntity;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.dto.TransferResponse;
import com.mainbank.ledger.repository.LedgerEntryRepository;
import com.mainbank.ledger.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransferLookupService {

    private final TransferRepository transfers;
    private final LedgerEntryRepository ledger;

    public TransferLookupService(TransferRepository transfers, LedgerEntryRepository ledger) {
        this.transfers = transfers;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public TransferResponse get(UUID id) {
        TransferEntity t = transfers.findById(id)
                .orElseThrow(() -> new DomainException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer " + id + " not found"));
        return TransferResponse.from(t, ledger.findByTransferIdOrderByIdAsc(id));
    }
}
