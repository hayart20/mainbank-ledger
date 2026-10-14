package com.mainbank.ledger.api;

import com.mainbank.ledger.service.TransferLookupService;
import com.mainbank.ledger.service.TransferService;
import com.mainbank.ledger.dto.TransferRequest;
import com.mainbank.ledger.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** Receipts are served by the receipt service: {@code GET /api/v1/receipts/{transferId}} on its port. */
@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Move money between two accounts of the same currency (double-entry, idempotent)")
public class TransferController {

    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    private final TransferService transfers;
    private final TransferLookupService queries;

    public TransferController(TransferService transfers, TransferLookupService queries) {
        this.transfers = transfers;
        this.queries = queries;
    }

    @Operation(
            summary = "Transfer money",
            description = """
                    Debits the source account and credits the target account atomically, writing a DEBIT and a \
                    CREDIT ledger entry.

                    **Idempotency:** send a UUID in `X-Idempotency-Key`, generated once per intended payment and \
                    reused on every retry of it. A retry with the same key and payload returns the original \
                    response with the `Idempotent-Replayed: true` header and moves no money. The same key with a \
                    different payload is rejected with IDEMPOTENCY_KEY_REUSED. A failed transfer is not cached, so \
                    it can be retried with the same key.

                    The transfer is announced to the other services through Kafka, so its receipt and statement \
                    line appear shortly after this call returns.""")
    @ApiResponse(responseCode = "201", description = "Transfer created",
            headers = @Header(name = "Location", description = "URL of the new transfer"))
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED, MALFORMED_REQUEST (e.g. unknown currency) or MISSING_HEADER",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND: source or target account does not exist",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = """
            Business rule violated: INSUFFICIENT_FUNDS, ACCOUNT_NOT_ACTIVE, SAME_ACCOUNT, INVALID_AMOUNT, \
            CBA_LIMIT_EXCEEDED, SYSTEM_ACCOUNT_NOT_ALLOWED, CURRENCY_MISMATCH or IDEMPOTENCY_KEY_REUSED""",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "LOCK_TIMEOUT: an account is busy, retry (see Retry-After)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @Parameter(description = "Client-generated UUID identifying this payment; reuse it on retries",
                    example = "7d3f2c9e-5a1b-4c8d-9e0f-1a2b3c4d5e6f")
            @RequestHeader(IDEMPOTENCY_HEADER) UUID idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferService.Result result = transfers.execute(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status())
                .location(URI.create("/api/v1/transfers/" + result.body().id()));
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.body());
    }

    @Operation(summary = "Get a transfer", description = "Returns the transfer with its DEBIT and CREDIT ledger entries.")
    @ApiResponse(responseCode = "200", description = "The transfer")
    @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: id is not a valid UUID",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "TRANSFER_NOT_FOUND",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public TransferResponse get(@Parameter(description = "Transfer id") @PathVariable UUID id) {
        return queries.get(id);
    }
}
