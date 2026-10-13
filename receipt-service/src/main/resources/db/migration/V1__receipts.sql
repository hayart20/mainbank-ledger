-- Owned by the receipt service: lives in its own schema (see spring.datasource.url currentSchema).
CREATE TABLE receipts (
    transfer_id         UUID PRIMARY KEY,
    type                VARCHAR(20)    NOT NULL,
    transfer_created_at TIMESTAMPTZ    NOT NULL,
    source_iban         VARCHAR(34)    NOT NULL,
    source_owner        VARCHAR(200)   NOT NULL,
    target_iban         VARCHAR(34)    NOT NULL,
    target_owner        VARCHAR(200)   NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(10)    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    object_key          VARCHAR(255),
    details_sha256      VARCHAR(64),
    pdf_sha256          VARCHAR(64),
    attempts            INT            NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- the retry job only ever looks at receipts that are not done
CREATE INDEX ix_receipts_pending ON receipts (updated_at) WHERE status <> 'READY';
