package com.mainbank.receipt.exception;

/** A business-level failure with a stable code; the web layer maps the code to an HTTP status. */
public class ReceiptException extends RuntimeException {

    public enum Code { RECEIPT_NOT_FOUND, RECEIPT_NOT_READY }

    private final Code code;

    public ReceiptException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
