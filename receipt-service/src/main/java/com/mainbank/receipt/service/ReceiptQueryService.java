package com.mainbank.receipt.service;

import com.mainbank.receipt.domain.Receipt;
import com.mainbank.receipt.domain.ReceiptStatus;
import com.mainbank.receipt.dto.ReceiptLinkResponse;
import com.mainbank.receipt.exception.ReceiptException;
import com.mainbank.receipt.repository.ReceiptRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ReceiptQueryService {

    private final ReceiptRepository receipts;
    private final ReceiptStorage storage;

    public ReceiptQueryService(ReceiptRepository receipts, ReceiptStorage storage) {
        this.receipts = receipts;
        this.storage = storage;
    }

    public ReceiptLinkResponse link(UUID transferId) {
        Receipt r = ready(transferId);
        ReceiptStorage.Link link = storage.presignDownload(r.getObjectKey(), filename(transferId));
        return new ReceiptLinkResponse(link.url(), link.expiresAt(), r.getDetailsSha256());
    }

    public byte[] bytes(UUID transferId) {
        return storage.get(ready(transferId).getObjectKey());
    }

    private Receipt ready(UUID transferId) {
        Receipt r = receipts.findById(transferId)
                .orElseThrow(() -> new ReceiptException(ReceiptException.Code.RECEIPT_NOT_FOUND,
                        "No receipt for transfer " + transferId));
        if (r.getStatus() != ReceiptStatus.READY) {
            throw new ReceiptException(ReceiptException.Code.RECEIPT_NOT_READY,
                    "Receipt for transfer " + transferId + " is " + r.getStatus() + "; retry shortly");
        }
        return r;
    }

    public static String filename(UUID transferId) {
        return "receipt-" + transferId + ".pdf";
    }
}
