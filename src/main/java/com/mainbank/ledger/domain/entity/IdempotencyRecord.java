package com.mainbank.ledger.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.util.UUID;

@Getter
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
}
