package com.mainbank.ledger.api;

import com.mainbank.ledger.service.TransferLookupService;
import com.mainbank.ledger.service.TransferService;
import com.mainbank.ledger.dto.TransferRequest;
import com.mainbank.ledger.dto.TransferResponse;
import jakarta.validation.Valid;
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
public class TransferController {

    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    private final TransferService transfers;
    private final TransferLookupService queries;

    public TransferController(TransferService transfers, TransferLookupService queries) {
        this.transfers = transfers;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(@RequestHeader(IDEMPOTENCY_HEADER) UUID idempotencyKey,
                                                   @Valid @RequestBody TransferRequest request) {
        TransferService.Result result = transfers.execute(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status())
                .location(URI.create("/api/v1/transfers/" + result.body().id()));
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id) {
        return queries.get(id);
    }
}
