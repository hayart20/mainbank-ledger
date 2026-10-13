package com.mainbank.ledger.domain.constant;

/** Stable, transport-agnostic business error codes. Mapping to HTTP lives in the web layer. */
public enum ErrorCode {
    INSUFFICIENT_FUNDS,
    ACCOUNT_NOT_ACTIVE,
    ACCOUNT_NOT_FOUND,
    TRANSFER_NOT_FOUND,
    IDEMPOTENCY_KEY_REUSED,
    SAME_ACCOUNT,
    INVALID_AMOUNT,
    CBA_LIMIT_EXCEEDED,
    SYSTEM_ACCOUNT_NOT_ALLOWED,
    CURRENCY_MISMATCH
}
