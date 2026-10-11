package com.mainbank.ledger.domain.entity;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key")
    private UUID key;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    protected IdempotencyRecord() { }

    public UUID getKey() { return key; }

    public String getRequestHash() { return requestHash; }

    public UUID getTransferId() { return transferId; }

    public Integer getResponseStatus() { return responseStatus; }

    public String getResponseBody() { return responseBody; }
}
