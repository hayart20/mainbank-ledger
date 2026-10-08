package com.mainbank.ledger.dto;

import java.time.Instant;

public record ReceiptLinkResponse(String url, Instant expiresAt, String sha256) {
}
