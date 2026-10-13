-- Owned by the statement service: lives in its own schema (see spring.datasource.url currentSchema).
-- A read model built from transferEntity-completed events: one row per accountEntity touched by a transferEntity.
CREATE TABLE statement_lines (
    transfer_id             UUID           NOT NULL,
    account_id              UUID           NOT NULL,
    entry_type              VARCHAR(6)     NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    transfer_type           VARCHAR(20)    NOT NULL,
    occurred_at             TIMESTAMPTZ    NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3)     NOT NULL,
    counterparty_account_id UUID           NOT NULL,
    counterparty_iban       VARCHAR(34)    NOT NULL,
    counterparty_owner      VARCHAR(200)   NOT NULL,
    PRIMARY KEY (transfer_id, account_id)
);

CREATE INDEX ix_statement_account_time ON statement_lines (account_id, occurred_at DESC);
