package com.mainbank.receipt.api;

import com.mainbank.receipt.service.ReceiptQueryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    private final ReceiptQueryService queries;

    public ReceiptController(ReceiptQueryService queries) {
        this.queries = queries;
    }

    /** {@code mode=url} (default) returns a 15-minute pre-signed link; {@code mode=stream} pipes the PDF. */
    @GetMapping("/{transferId}")
    public ResponseEntity<?> receipt(@PathVariable UUID transferId, @RequestParam(defaultValue = "url") String mode) {
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
