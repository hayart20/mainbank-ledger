package com.mainbank.receipt.dto;

import java.time.Instant;

/** @param sha256 SHA-256 of the transaction details printed on the PDF (CBA digital audit trail) */
public record ReceiptLinkResponse(String url, Instant expiresAt, String sha256) {
}
