CREATE SEQUENCE iban_account_seq START WITH 1000000000 INCREMENT BY 1;

CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    iban            VARCHAR(34)    NOT NULL,
    owner_name      VARCHAR(200)   NOT NULL,
    currency        VARCHAR(3)     NOT NULL CHECK (currency IN ('AMD', 'USD', 'EUR', 'RUB')),
    balance         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    overdraft_limit NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (overdraft_limit >= 0),
    status          VARCHAR(10)    NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN')),
    system_account  BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_iban UNIQUE (iban),
    -- last line of defence: even a buggy service can never overdraw beyond the agreed limit
    CONSTRAINT ck_accounts_balance CHECK (balance >= -overdraft_limit)
);

CREATE TABLE transfers (
    id                 UUID PRIMARY KEY,
    idempotency_key    UUID           NOT NULL,
    type               VARCHAR(20)    NOT NULL CHECK (type IN ('TRANSFER', 'OPENING_DEPOSIT')),
    source_account_id  UUID           NOT NULL REFERENCES accounts (id),
    target_account_id  UUID           NOT NULL REFERENCES accounts (id),
    amount             NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency           VARCHAR(3)     NOT NULL,
    status             VARCHAR(20)    NOT NULL,
    receipt_status     VARCHAR(10)    NOT NULL DEFAULT 'PENDING',
    receipt_object_key VARCHAR(255),
    receipt_sha256     VARCHAR(64),
    receipt_pdf_sha256 VARCHAR(64),
    receipt_attempts   INT            NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_transfers_distinct CHECK (source_account_id <> target_account_id)
);
CREATE UNIQUE INDEX uq_transfers_idempotency_key ON transfers (idempotency_key);
CREATE INDEX ix_transfers_receipt_pending ON transfers (created_at) WHERE receipt_status <> 'READY';

CREATE TABLE ledger_entries (
    id         BIGSERIAL PRIMARY KEY,
    transfer_id UUID           NOT NULL REFERENCES transfers (id),
    account_id UUID           NOT NULL REFERENCES accounts (id),
    entry_type VARCHAR(6)     NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount     NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ix_ledger_transfer ON ledger_entries (transfer_id);
CREATE INDEX ix_ledger_account ON ledger_entries (account_id, created_at);

CREATE TABLE idempotency_keys (
    idempotency_key UUID PRIMARY KEY,
    request_hash    VARCHAR(64)    NOT NULL,
    transfer_id     UUID,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);