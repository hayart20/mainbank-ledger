package com.mainbank.ledger.domain.exception;

import com.mainbank.ledger.domain.constant.ErrorCode;

/** Business-rule violation. Knows nothing about HTTP; the web layer maps {@link ErrorCode} to a status. */
public class DomainException extends RuntimeException {
    private final ErrorCode code;

    public DomainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
