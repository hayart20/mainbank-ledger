package com.mainbank.statement.service;

import com.mainbank.ledger.events.TransferCompletedMessage;
import com.mainbank.statement.dto.StatementResponse;
import com.mainbank.statement.repository.StatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class StatementService {

    public static final int MAX_PAGE_SIZE = 200;

    /** Deep offsets scan and discard every skipped row; beyond this a period filter is the right tool. */
    static final long MAX_OFFSET = 100_000;

    private final StatementRepository lines;

    public StatementService(StatementRepository lines) {
        this.lines = lines;
    }

    /**
     * Records both sides of the transfer in one transaction, so an account never shows a movement whose
     * counterpart is missing. Redelivery is harmless: each row is inserted at most once.
     */
    @Transactional
    public void onTransferCompleted(TransferCompletedMessage m) {
        lines.insertIfAbsent(m.transferId(), m.sourceAccountId(), "DEBIT", m.type(), m.createdAt(), m.amount(),
                m.currency(), m.targetAccountId(), m.targetIban(), m.targetOwner());
        lines.insertIfAbsent(m.transferId(), m.targetAccountId(), "CREDIT", m.type(), m.createdAt(), m.amount(),
                m.currency(), m.sourceAccountId(), m.sourceIban(), m.sourceOwner());
    }

    /**
     * @param from inclusive start of the period, or null
     * @param to   exclusive end of the period, or null
     */
    @Transactional(readOnly = true)
    public StatementResponse statement(UUID accountId, Instant from, Instant to, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        long offset = (long) page * size;
        if (offset > MAX_OFFSET) {
            throw new IllegalArgumentException("page is too large; narrow the period with 'from' and 'to' instead");
        }
        var totals = lines.totals(accountId, from, to);
        var content = lines.page(accountId, from, to, size, offset);
        return new StatementResponse(accountId, from, to, page, size, totals.lines(), totals.debits(), totals.credits(), content);
    }
}
