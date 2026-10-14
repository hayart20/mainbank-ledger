package com.mainbank.statement.api;

import com.mainbank.statement.dto.StatementResponse;
import com.mainbank.statement.service.StatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/statement")
@Tag(name = "Statements", description = "Account activity, built from completed-transfer events")
public class StatementController {

    private final StatementService statements;

    public StatementController(StatementService statements) {
        this.statements = statements;
    }

    /**
     * Account activity, newest first. {@code from} is inclusive and {@code to} exclusive (ISO-8601 instants);
     * an account without movements returns an empty page, not 404, because this service does not own accounts.
     */
    @Operation(
            summary = "Get an account statement",
            description = """
                    Lists the movements of an account, newest first, with debit and credit totals for the whole \
                    period. The statement is built asynchronously from ledger events, so a transfer shows up a \
                    moment after it completes.

                    An account without movements (or an unknown account) returns an empty page, not 404, because \
                    this service does not own accounts.""")
    @ApiResponse(responseCode = "200", description = "A page of the statement")
    @ApiResponse(responseCode = "400",
            description = "INVALID_PARAMETER: bad id or date, page < 0, size outside 1-200, from not before to, or page too deep (narrow the period)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public StatementResponse statement(
            @Parameter(description = "Account id") @PathVariable UUID accountId,
            @Parameter(description = "Inclusive start of the period, ISO-8601 instant", example = "2026-01-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Exclusive end of the period, ISO-8601 instant", example = "2026-02-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, 1-200") @RequestParam(defaultValue = "50") int size) {
        return statements.statement(accountId, from, to, page, size);
    }
}
