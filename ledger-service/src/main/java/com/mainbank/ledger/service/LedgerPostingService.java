package com.mainbank.ledger.service;

import com.mainbank.ledger.config.LedgerProperties;
import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.EntryType;
import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.constant.TransferType;
import com.mainbank.ledger.domain.entity.AccountEntity;
import com.mainbank.ledger.domain.entity.LedgerEntryEntity;
import com.mainbank.ledger.domain.entity.TransferEntity;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.messaging.TransferCompletedMessage;
import com.mainbank.ledger.messaging.TransferEventProducer;
import com.mainbank.ledger.repository.AccountRepository;
import com.mainbank.ledger.repository.LedgerEntryRepository;
import com.mainbank.ledger.repository.TransferRepository;
import com.mainbank.ledger.util.SystemAccounts;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The money-moving core, shared by customer transfers and opening deposits: lock both accounts in a fixed order,
 * debit and credit them, and write the transfer plus its DEBIT and CREDIT ledger entries.
 * <p>
 * It knows nothing about idempotency keys, API limits or HTTP; those belong to the callers. Every method
 * requires an existing transaction ({@link Propagation#MANDATORY}) because row locks are only meaningful
 * inside the caller's transaction, which also decides whether the whole operation commits or rolls back.
 */
@Service
public class LedgerPostingService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledger;
    private final TransferEventProducer events;
    private final EntityManager em;
    private final LedgerProperties props;

    public LedgerPostingService(AccountRepository accounts, TransferRepository transfers, LedgerEntryRepository ledger,
                                TransferEventProducer events, EntityManager em, LedgerProperties props) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.events = events;
        this.em = em;
        this.props = props;
    }

    /**
     * Limits how long this transaction may wait for any lock ({@code ledger.lock-timeout}), so a stuck lock
     * holder produces a quick {@code LOCK_TIMEOUT} error instead of queueing requests forever. It applies to the
     * current transaction only ({@code set_config(..., true)} is the parameterised form of {@code SET LOCAL}).
     * Callers invoke it first thing in their transaction, before anything that can block.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyLockTimeout() {
        em.createNativeQuery("select set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", props.lockTimeout().toMillis() + "ms")
                .getSingleResult();
    }

    /**
     * Posts one money movement: debits the source account, credits the target account, and records the transfer
     * with its two ledger entries (a DEBIT leg and a CREDIT leg of the same amount). This is the only place where
     * balances change, so every balance change is backed by a balanced pair of ledger rows.
     * <p>
     * Steps, all inside the caller's transaction:
     * <ol>
     *   <li><b>Basic checks:</b> source and target must differ, and the amount must be positive. The amount is
     *       scaled to exactly 2 decimals.</li>
     *   <li><b>Lock both accounts</b> with {@code SELECT ... FOR UPDATE}, always the account with the lower id
     *       first, whatever the direction of the transfer. If A->B and B->A run at the same time, both lock
     *       the same account first, so one simply waits for the other and a deadlock cannot occur. The row locks
     *       also make the balance check and update atomic, so two concurrent debits cannot spend the same money.
     *       The locked rows are then mapped back to source and target.</li>
     *   <li><b>Validate the locked accounts:</b> a bank settlement account may not be debited unless
     *       {@code allowSystemSource} is true, and both accounts must be in the transfer currency. Checks run
     *       after locking because they need the current, locked state.</li>
     *   <li><b>Move the money:</b> {@link AccountEntity#debit} and {@link AccountEntity#credit} apply the status and overdraft
     *       rules and update the balances in memory.</li>
     *   <li><b>Persist:</b> save the transfer and both ledger entries, then
     *       flush, so database constraints such as {@code balance >= -overdraft_limit} are checked now and not
     *       at commit time.</li>
     * </ol>
     * Any failure throws and the caller's transaction rolls back, leaving no partial debit, credit or ledger row.
     * The completed transfer is announced through the transactional outbox, written in this same transaction, so the
     * event exists if and only if the money movement commits.
     *
     * @param idempotencyKey    stored on the transfer; the client's key, or a random UUID for internal postings
     * @param type              {@link TransferType#TRANSFER} or {@link TransferType#OPENING_DEPOSIT}
     * @param sourceId          account to debit
     * @param targetId          account to credit
     * @param rawAmount         positive amount, at most 2 decimal places
     * @param currency          must equal the currency of both accounts
     * @param allowSystemSource false for customer transfers, so the bank's settlement accounts cannot be
     *                          debited through the API; true only for internal postings such as opening deposits
     * @return the saved transfer
     * @throws DomainException {@code SAME_ACCOUNT}, {@code INVALID_AMOUNT}, {@code ACCOUNT_NOT_FOUND},
     *                         {@code SYSTEM_ACCOUNT_NOT_ALLOWED}, {@code CURRENCY_MISMATCH},
     *                         {@code ACCOUNT_NOT_ACTIVE} or {@code INSUFFICIENT_FUNDS}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransferEntity post(UUID idempotencyKey, TransferType type, UUID sourceId, UUID targetId, BigDecimal rawAmount,
                               Currency currency, boolean allowSystemSource) {
        if (sourceId.equals(targetId)) {
            throw new DomainException(ErrorCode.SAME_ACCOUNT, "Source and target accounts must differ");
        }
        BigDecimal amount = rawAmount.setScale(2);
        if (amount.signum() <= 0) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT, "Amount must be positive");
        }

        // Deterministic global lock order (ascending id) makes A->B and B->A transfers deadlock-free.
        boolean sourceHasLowerId = sourceId.compareTo(targetId) < 0;
        UUID lowerId = sourceHasLowerId ? sourceId : targetId;
        UUID higherId = sourceHasLowerId ? targetId : sourceId;

        AccountEntity lowerIdAccountEntity = lock(lowerId);
        AccountEntity higherIdAccountEntity = lock(higherId);

        // Lock order is by id, not by role, so map the locked rows back to their roles in this transfer.
        AccountEntity source = sourceHasLowerId ? lowerIdAccountEntity : higherIdAccountEntity;
        AccountEntity target = sourceHasLowerId ? higherIdAccountEntity : lowerIdAccountEntity;

        if (!allowSystemSource && source.isSystemAccount()) {
            throw new DomainException(ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED, "Bank settlement accounts cannot be debited via the API");
        }
        if (source.getCurrency() != currency || target.getCurrency() != currency) {
            throw new DomainException(ErrorCode.CURRENCY_MISMATCH, "Transfer currency %s does not match accounts (%s -> %s)"
                    .formatted(currency, source.getCurrency(), target.getCurrency()));
        }

        source.debit(amount);
        target.credit(amount);

        TransferEntity transferEntity = transfers.save(
                TransferEntity.completed(idempotencyKey, type, sourceId, targetId, amount, currency));
        ledger.saveAll(List.of(
                new LedgerEntryEntity(transferEntity.getId(), sourceId, EntryType.DEBIT, amount),
                new LedgerEntryEntity(transferEntity.getId(), targetId, EntryType.CREDIT, amount)));
        accounts.flush();
        // Every posting is announced through the outbox (same transaction), with everything consumers need.
        events.record(new TransferCompletedMessage(TransferCompletedMessage.SCHEMA_VERSION, transferEntity.getId(),
                type.name(), transferEntity.getCreatedAt(), source.getId(), source.getIban(), source.getOwnerName(),
                target.getId(), target.getIban(), target.getOwnerName(), amount, currency.name()));
        return transferEntity;
    }

    /**
     * Funds a freshly opened account from the bank settlement account of the same currency, so the opening
     * balance is a real double-entry posting and never a direct balance write. It is announced like for
     * any other money movement.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransferEntity openingDeposit(AccountEntity target, BigDecimal amount) {
        return post(UUID.randomUUID(), TransferType.OPENING_DEPOSIT,
                SystemAccounts.settlementFor(target.getCurrency()), target.getId(), amount, target.getCurrency(), true);
    }

    private AccountEntity lock(UUID id) {
        return accounts.findByIdForUpdate(id)
                .orElseThrow(() -> new DomainException(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + id + " not found"));
    }
}
