package com.mainbank.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.ledger.config.LedgerProperties;
import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.constant.TransferType;
import com.mainbank.ledger.domain.entity.IdempotencyRecord;
import com.mainbank.ledger.domain.entity.Transfer;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.dto.TransferRequest;
import com.mainbank.ledger.dto.TransferResponse;
import com.mainbank.ledger.messaging.TransferEventProducer;
import com.mainbank.ledger.repository.IdempotencyRepository;
import com.mainbank.ledger.repository.LedgerEntryRepository;
import com.mainbank.ledger.util.Hashing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Customer-facing transfer use case: idempotency, the CBA single-transfer limit and the cached response.
 * The actual money movement is delegated to {@link LedgerPostingService}. One database transaction covers:
 * lock timeout -> idempotency claim -> posting (ordered row locks, balances, ledger legs) -> cached response.
 * Either everything commits or nothing does.
 */
@Service
public class TransferService {

    public static final int CREATED = 201;

    /** @param replayed true when the response came from the idempotency cache */
    public record Result(TransferResponse body, int status, boolean replayed) { }

    private final LedgerPostingService ledgerPostingService;
    private final LedgerEntryRepository ledger;
    private final IdempotencyRepository idempotency;

    /**
     * Announces a completed transfer to the receipt pipeline over Kafka, without {@code TransferService}
     * depending on how receipts are made. The producer sends only after this transaction commits, so a
     * rolled-back transfer produces no receipt, and the PDF/MinIO work happens in the Kafka consumer, outside the
     * money transaction and its row locks. A lost message is recovered by the receipt retry job.
     */
    private final TransferEventProducer events;

    private final ObjectMapper json;
    private final LedgerProperties props;

    public TransferService(LedgerPostingService ledgerPostingService, LedgerEntryRepository ledger, IdempotencyRepository idempotency,
                           TransferEventProducer events, ObjectMapper json, LedgerProperties props) {
        this.ledgerPostingService = ledgerPostingService;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.events = events;
        this.json = json;
        this.props = props;
    }

    /**
     * Executes a transfer exactly once per {@code idempotencyKey}, however many times the client sends it.
     * <p>
     * <b>Why a key:</b> a client that times out or loses the connection does not know whether its transfer
     * happened, so it retries. Without protection the retry would move the money twice. The client therefore
     * sends a UUID in the {@code X-Idempotency-Key} header, generated once per intended payment and reused on every
     * retry of that payment.
     * <p>
     * <b>How it works</b>, all in one database transaction:
     * <ol>
     *   <li>{@link LedgerPostingService#applyLockTimeout()} bounds every lock wait below.</li>
     *   <li>{@code idempotency.tryClaim(key, hash)} tries to insert the key into {@code idempotency_keys}
     *       ({@code INSERT ... ON CONFLICT DO NOTHING}) together with a SHA-256 hash of the request. It returns
     *       1 if this request is the first one with that key, and 0 if the key already exists.</li>
     *   <li>If the key already existed, nothing is executed: {@link #replay} returns the response stored with the
     *       first request, or fails with {@code IDEMPOTENCY_KEY_REUSED} if the hash differs, meaning the client
     *       reused the key for a different payment.</li>
     *   <li>Otherwise the CBA limit is checked, the money is moved by {@link LedgerPostingService#post}, the
     *       receipt event is published and the response is stored under the key
     *       ({@code idempotency.complete}).</li>
     * </ol>
     * <b>Concurrent duplicates:</b> the claim and the transfer share one transaction. If two identical requests
     * arrive at the same moment, the second {@code tryClaim} blocks on the unique index until the first
     * transaction ends. If the first commits, the second gets 0 and replays the stored response, so exactly one
     * transfer exists. If the first rolls back (for example insufficient funds), its key row disappears too, and
     * the second request runs normally. This is also why a failed transfer is not cached: a retry is evaluated
     * again against the current balances.
     * <p>
     * <b>Isolation:</b> {@code READ_COMMITTED}, stated explicitly. It is sufficient because correctness does not
     * rely on snapshot reads: every balance is read under {@code SELECT ... FOR UPDATE} (which returns the latest
     * committed row once the lock is granted), and duplicates are decided by the unique index. A stricter level
     * (REPEATABLE READ / SERIALIZABLE) would only add serialization failures and retries on top of the locks.
     *
     * @param idempotencyKey client-supplied key from the {@code X-Idempotency-Key} header
     * @param request        source and target account, amount and currency
     * @return the created transfer, or the stored response of the first request when {@code replayed} is true
     * @throws DomainException {@code IDEMPOTENCY_KEY_REUSED}, {@code CBA_LIMIT_EXCEEDED}, or any posting error
     *                         such as {@code INSUFFICIENT_FUNDS}, {@code ACCOUNT_NOT_ACTIVE}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result execute(UUID idempotencyKey, TransferRequest request) {
        // First, so it also covers the idempotency claim below, which can block on a concurrent duplicate.
        ledgerPostingService.applyLockTimeout();

        String requestHash = Hashing.sha256Hex(request.canonical());

        // Blocks on the unique index while a concurrent request with the same key is still in flight.
        if (idempotency.tryClaim(idempotencyKey, requestHash) == 0) {
            return replay(idempotencyKey, requestHash);
        }

        if (request.amount().setScale(2).compareTo(props.maxTransferAmount()) > 0) {
            throw new DomainException(ErrorCode.CBA_LIMIT_EXCEEDED,
                    "Amount exceeds the single-transfer limit of " + props.maxTransferAmount().toPlainString());
        }

        Transfer transfer = ledgerPostingService.post(idempotencyKey, TransferType.TRANSFER, request.sourceAccountId(),
                request.targetAccountId(), request.amount(), request.currency(), false);
        events.publishAfterCommit(transfer.getId());

        TransferResponse body = TransferResponse.from(transfer, ledger.findByTransferIdOrderByIdAsc(transfer.getId()), null);
        idempotency.complete(idempotencyKey, transfer.getId(), CREATED, serialize(body));
        return new Result(body, CREATED, false);
    }

    private Result replay(UUID key, String requestHash) {
        IdempotencyRecord record = idempotency.findById(key).orElseThrow();
        if (!record.getRequestHash().equals(requestHash)) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key was already used with a different request payload");
        }
        return new Result(deserialize(record.getResponseBody()), record.getResponseStatus(), true);
    }

    private String serialize(TransferResponse body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private TransferResponse deserialize(String body) {
        try {
            return json.readValue(body, TransferResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
