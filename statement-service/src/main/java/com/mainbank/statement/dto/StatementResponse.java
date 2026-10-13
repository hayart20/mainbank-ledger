package com.mainbank.statement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A page of an account statement, newest first.
 *
 * @param totalDebits  sum of all DEBIT lines in the requested period (not just this page)
 * @param totalCredits sum of all CREDIT lines in the requested period (not just this page)
 * @param totalLines   number of lines in the requested period
 */
public record StatementResponse(UUID accountId, Instant from, Instant to, int page, int size, long totalLines,
                                BigDecimal totalDebits, BigDecimal totalCredits, List<StatementLine> lines) {
}
