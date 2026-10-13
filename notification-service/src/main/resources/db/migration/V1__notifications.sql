-- Owned by the notification service: lives in its own schema (see spring.datasource.url currentSchema).
CREATE TABLE notifications (
    transfer_id UUID         NOT NULL,
    role        VARCHAR(6)   NOT NULL CHECK (role IN ('DEBIT', 'CREDIT')),
    recipient   VARCHAR(200) NOT NULL,
    iban        VARCHAR(34)  NOT NULL,
    message     TEXT         NOT NULL,
    sent_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (transfer_id, role)
);
