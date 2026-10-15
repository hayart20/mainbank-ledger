package com.mainbank.receipt.api;

import com.mainbank.receipt.dto.ReceiptLinkResponse;
import com.mainbank.receipt.service.ReceiptQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/receipts")
@Tag(name = "Receipts", description = "PDF receipts of completed transfers, generated asynchronously from ledger events")
public class ReceiptController {

    private final ReceiptQueryService queries;

    public ReceiptController(ReceiptQueryService queries) {
        this.queries = queries;
    }

    /** {@code mode=url} (default) returns a 15-minute pre-signed link; {@code mode=stream} pipes the PDF. */
    @Operation(
            summary = "Get the receipt of a transfer",
            description = """
                    Returns the PDF receipt of a completed transfer. The receipt is built asynchronously after the \
                    transfer commits, so right after a transfer it may not be ready yet: retry shortly.

                    * `mode=url` (default): JSON with a pre-signed download link valid for 15 minutes.
                    * `mode=stream`: the PDF itself as an attachment.""")
    @ApiResponse(responseCode = "200", description = "`mode=url`: JSON with the link; `mode=stream`: the PDF file",
            content = {
                    @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ReceiptLinkResponse.class)),
                    @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: transferId is not a valid UUID",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "RECEIPT_NOT_FOUND: no receipt is known for this transfer",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "RECEIPT_NOT_READY: the receipt is still PENDING or FAILED, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "STORAGE_UNAVAILABLE: the object storage cannot be reached",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{transferId}")
    public ResponseEntity<?> receipt(
            @Parameter(description = "Id of the transfer (`id` of `POST /api/v1/transfers`)") @PathVariable UUID transferId,
            @Parameter(description = "`url` returns a pre-signed link as JSON, `stream` returns the PDF bytes",
                    schema = @Schema(allowableValues = {"url", "stream"}, defaultValue = "url"))
            @RequestParam(defaultValue = "url") String mode) {
        if ("stream".equalsIgnoreCase(mode)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + ReceiptQueryService.filename(transferId) + "\"")
                    .body(queries.bytes(transferId));
        }
        return ResponseEntity.ok(queries.link(transferId));
    }
}
