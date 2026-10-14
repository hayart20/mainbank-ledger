package com.mainbank.statement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
public record StatementResponse(
        UUID accountId,
        @Schema(description = "Inclusive start of the period as requested; absent when unbounded") Instant from,
        @Schema(description = "Exclusive end of the period as requested; absent when unbounded") Instant to,
        @Schema(description = "Zero-based page index") int page,
        @Schema(description = "Page size") int size,
        @Schema(description = "Number of lines in the whole period, not just this page") long totalLines,
        @Schema(description = "Sum of all DEBIT lines in the period", example = "250.00") BigDecimal totalDebits,
        @Schema(description = "Sum of all CREDIT lines in the period", example = "1000.00") BigDecimal totalCredits,
        @Schema(description = "The lines of this page, newest first") List<StatementLine> lines) {
}
