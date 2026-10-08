package com.mainbank.ledger.service;

import com.mainbank.ledger.service.receipt.ReceiptStorage;

import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.domain.constant.ReceiptStatus;
import com.mainbank.ledger.domain.entity.Transfer;
import com.mainbank.ledger.repository.LedgerEntryRepository;
import com.mainbank.ledger.repository.TransferRepository;
import com.mainbank.ledger.dto.ReceiptLinkResponse;
import com.mainbank.ledger.dto.TransferResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransferLookupService {

    private final TransferRepository transfers;
    private final LedgerEntryRepository ledger;
    private final ReceiptStorage storage;

    public TransferLookupService(TransferRepository transfers, LedgerEntryRepository ledger, ReceiptStorage storage) {
        this.transfers = transfers;
        this.ledger = ledger;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public TransferResponse get(UUID id) {
        Transfer t = find(id);
        String url = t.getReceiptStatus() == ReceiptStatus.READY
                ? storage.presignDownload(t.getReceiptObjectKey(), filename(t)).url() : null;
        return TransferResponse.from(t, ledger.findByTransferIdOrderByIdAsc(id), url);
    }

    @Transactional(readOnly = true)
    public ReceiptLinkResponse receiptLink(UUID id) {
        Transfer t = readyReceipt(id);
        ReceiptStorage.Link link = storage.presignDownload(t.getReceiptObjectKey(), filename(t));
        return new ReceiptLinkResponse(link.url(), link.expiresAt(), t.getReceiptSha256());
    }

    @Transactional(readOnly = true)
    public byte[] receiptBytes(UUID id) {
        return storage.get(readyReceipt(id).getReceiptObjectKey());
    }

    private Transfer readyReceipt(UUID id) {
        Transfer t = find(id);
        if (t.getReceiptStatus() != ReceiptStatus.READY) {
            throw new DomainException(ErrorCode.RECEIPT_NOT_READY, "Receipt for transfer " + id + " is " + t.getReceiptStatus() + "; retry shortly");
        }
        return t;
    }

    private Transfer find(UUID id) {
        return transfers.findById(id)
                .orElseThrow(() -> new DomainException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer " + id + " not found"));
    }

    private static String filename(Transfer t) {
        return "receipt-" + t.getId() + ".pdf";
    }
}
