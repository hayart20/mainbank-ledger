package com.mainbank.ledger.storage;

/** A failed object-storage call: either an S3 error response (status and code set) or a transport failure. */
public class MinioException extends RuntimeException {

    private final int status;
    private final String code;

    public MinioException(String message, int status, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public MinioException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
        this.code = null;
    }

    /** HTTP status of the S3 error response, or -1 for a transport failure. */
    public int status() {
        return status;
    }

    /** S3 error code such as {@code NoSuchBucket} or {@code NoSuchKey}; null for a transport failure. */
    public String code() {
        return code;
    }

    public boolean isNoSuchBucket() {
        return "NoSuchBucket".equals(code);
    }
}
