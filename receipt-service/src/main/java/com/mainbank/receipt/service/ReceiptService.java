package com.mainbank.receipt.service;

import com.mainbank.receipt.messaging.TransferCompletedMessage;
import com.mainbank.receipt.config.ReceiptProperties;
import com.mainbank.receipt.domain.Receipt;
import com.mainbank.receipt.domain.ReceiptStatus;
import com.mainbank.receipt.repository.ReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the receipt for every announced transfer. Not transactional on purpose: PDF generation and MinIO I/O
 * never hold a database transaction, and each repository call commits on its own.
 * <p>
 * Everything here is idempotent, so a redelivered Kafka message or a retry is harmless: the row is created at
 * most once, an already READY receipt is never regenerated, and the object key is derived from the transfer id.
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    private final ReceiptRepository receipts;
    private final ReceiptPdfGenerator pdf;
    private final ReceiptStorage storage;
    private final ReceiptProperties props;

    public ReceiptService(ReceiptRepository receipts, ReceiptPdfGenerator pdf, ReceiptStorage storage,
                          ReceiptProperties props) {
        this.receipts = receipts;
        this.pdf = pdf;
        this.storage = storage;
        this.props = props;
    }

    /** Kafka path: record the facts from the event, then produce the receipt. */
    public void onTransferCompleted(TransferCompletedMessage m) {
        receipts.insertIfAbsent(m.transferId(), m.type(), m.createdAt(), m.sourceIban(), m.sourceOwner(),
                m.targetIban(), m.targetOwner(), m.amount(), m.currency());
        generate(m.transferId());
    }

    /** Failures are recorded and left to {@link #retryPending()}; they never propagate to the Kafka listener. */
    void generate(UUID transferId) {
        Receipt receipt = receipts.findById(transferId).orElse(null);
        if (receipt == null || receipt.getStatus() == ReceiptStatus.READY) {
            return;
        }
        try {
            ReceiptData data = ReceiptData.of(receipt);
            byte[] bytes = pdf.generate(data);
            String key = "receipts/" + transferId + ".pdf";
            storage.put(key, bytes, data.detailsHash());
            receipts.markReady(transferId, key, data.detailsHash(), ReceiptData.sha256Hex(bytes), Instant.now());
            log.info("Receipt stored for transfer {} at {}", transferId, key);
        } catch (Exception e) {
            log.warn("Receipt generation failed for transfer {}: {}", transferId, e.toString());
            receipts.markFailed(transferId, Instant.now());
        }
    }

    /** Covers MinIO outages and crashes between recording the event and uploading the PDF. */
    @Scheduled(fixedDelayString = "${receipt.retry-interval}")
    public void retryPending() {
        Instant olderThan = Instant.now().minus(props.retryMinAge());
        for (Receipt candidate : receipts.findRetryable(props.maxAttempts(), olderThan, PageRequest.of(0, 50))) {
            // only one replica wins the claim; losers skip the receipt
            if (receipts.claim(candidate.getTransferId(), candidate.getUpdatedAt(), Instant.now()) == 1) {
                generate(candidate.getTransferId());
            }
        }
    }
}
