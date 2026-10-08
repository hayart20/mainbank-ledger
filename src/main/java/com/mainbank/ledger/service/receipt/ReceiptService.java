package com.mainbank.ledger.service.receipt;

import com.mainbank.ledger.event.TransferCompletedEvent;
import com.mainbank.ledger.util.Hashing;

import com.mainbank.ledger.config.LedgerProperties;
import com.mainbank.ledger.domain.entity.Account;
import com.mainbank.ledger.domain.constant.ReceiptStatus;
import com.mainbank.ledger.domain.entity.Transfer;
import com.mainbank.ledger.repository.AccountRepository;
import com.mainbank.ledger.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates the CBA audit receipt outside the money transaction: PDF + MinIO I/O must never hold row locks
 * or be able to fail a transfer. Failures are recorded and retried by {@link #retryPending()}.
 * Not transactional on purpose - each repository call commits on its own.
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final ReceiptPdfGenerator pdf;
    private final ReceiptStorage storage;
    private final LedgerProperties props;

    /** Single-instance guard against the async listener and the retry job racing on the same transfer. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public ReceiptService(TransferRepository transfers, AccountRepository accounts, ReceiptPdfGenerator pdf,
                          ReceiptStorage storage, LedgerProperties props) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.pdf = pdf;
        this.storage = storage;
        this.props = props;
    }

    @Async("receiptExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferCompleted(TransferCompletedEvent event) {
        generateAndStore(event.transferId());
    }

    public void generateAndStore(UUID transferId) {
        if (!inFlight.add(transferId)) return;
        try {
            Transfer transfer = transfers.findById(transferId).orElse(null);
            if (transfer == null || transfer.getReceiptStatus() == ReceiptStatus.READY) return;

            Account source = accounts.findById(transfer.getSourceAccountId()).orElseThrow();
            Account target = accounts.findById(transfer.getTargetAccountId()).orElseThrow();
            ReceiptData data = ReceiptData.of(transfer, source, target);

            byte[] bytes = pdf.generate(data);
            String key = "receipts/" + transferId + ".pdf";
            storage.put(key, bytes, data.detailsHash());
            transfers.markReceiptReady(transferId, key, data.detailsHash(), Hashing.sha256Hex(bytes));
            log.info("Receipt stored for transfer {} at {}", transferId, key);
        } catch (Exception e) {
            log.warn("Receipt generation failed for transfer {}: {}", transferId, e.toString());
            transfers.markReceiptFailed(transferId);
        } finally {
            inFlight.remove(transferId);
        }
    }

    /** Covers MinIO outages, dropped async tasks and crashes between commit and upload. */
    @Scheduled(fixedDelayString = "${ledger.receipt.retry-interval}")
    public void retryPending() {
        var receipt = props.receipt();
        var ids = transfers.findReceiptsToRetry(receipt.maxAttempts(),
                Instant.now().minus(receipt.retryMinAge()), PageRequest.of(0, 50));
        ids.forEach(this::generateAndStore);
    }
}
