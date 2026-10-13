# Mainbank Ledger

Multi-currency double-entry ledger with ACID transfers and DB-backed idempotency, built as a small set of
microservices that talk through Kafka: the **ledger-service** moves money and announces every transfer, and
independent consumers react to it (receipts, notifications, statements) behind one **api-gateway**.
Spring Boot 3.5 · Java 25 · PostgreSQL 15 · Flyway · Kafka · JDK-only MinIO client (SigV4) · Testcontainers.

```
                              ┌─────────────▶ receipt-service :8081       PDF → MinIO, receipt API   (schema receipt)
 client ─▶ api-gateway :8080 ─┼─────────────▶ statement-service :8083    read model, statements     (schema statement)
              │               │                      ▲                         ▲
              ▼               │                      │ Kafka: ledger.transfer.completed (key = transferId, DLT)
        ledger-service :8080 ─┘──(outbox)────────────┴─────────────────────────┘
        accounts, transfers, ledger (schema public)                             ▼
                                                           notification-service :8082    notices      (schema notification)
```

| Service | Role | Owns |
|---|---|---|
| `api-gateway` | the only published port (`:8080`); routes `/api/v1/**` | nothing |
| `ledger-service` | accounts, transfers, double-entry ledger; **producer** | schema `public` |
| `receipt-service` | **consumer**: hash-stamped PDF receipts in MinIO, receipt API | schema `receipt` |
| `notification-service` | **consumer**: notifies payer and payee (log sender, pluggable) | schema `notification` |
| `statement-service` | **consumer**: read model of account activity, statement API | schema `statement` |

Each consumer has its own consumer group, so every event reaches all of them independently and a new consumer
needs no change to the ledger.

## Run

```bash
docker compose up --build          # postgres + minio + bucket + kafka + 4 services + api-gateway on :8080
mvn test                           # unit tests only (*Test, Surefire) for all modules - fast, no Docker needed
mvn verify                         # unit tests, then Testcontainers integration tests (*IT, Failsafe) - needs a Docker daemon
```

Unit and integration tests are separate: classes named `*Test` run in `mvn test`; classes named `*IT` (e.g.
`TransferIT`) run only in `mvn verify`, so `mvn test` stays green on a machine without Docker.

> **MinIO images.** The image named in the task (`minio/minio:RELEASE.2023-09-04...`) can no longer be pulled
> (Docker Hub and Quay both refuse it), so compose defaults to `cgr.dev/chainguard/minio` and `minio-client`.
> Override with `MINIO_IMAGE` / `MINIO_MC_IMAGE` if you have a mirror. Those images have no `curl`, hence the
> bash `/dev/tcp` health probe. The bucket is created by `minio-init` **and** by the app if missing.
>
> **Docker Engine 29+.** docker-java defaults to API 1.32, which the engine rejects; the failsafe config pins
> `api.version=1.41` for the integration-test JVM.

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/accounts` | `ownerName`, `currency` (AMD/USD/EUR/RUB), optional `initialBalance`, `overdraftLimit`. IBAN generated (AM, mod-97 valid) |
| GET | `/api/v1/accounts/{id}` | balance / status |
| POST | `/api/v1/transfers` | requires `X-Idempotency-Key: <uuid>` → 201 (or 201 + `Idempotent-Replayed: true`) |
| GET | `/api/v1/transfers/{id}` | status and ledger legs |
| GET | `/api/v1/receipts/{transferId}` | **receipt-service.** `?mode=url` (default, 15 min pre-signed URL) or `?mode=stream` (PDF piped); 409 until ready |
| GET | `/api/v1/accounts/{id}/statement` | **statement-service.** `from` (inclusive) / `to` (exclusive) ISO instants, `page`, `size` (max 200); lines newest first + period totals |

```bash
A=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
     -d '{"ownerName":"Anna Petrosyan","currency":"AMD","initialBalance":1000000}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
B=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
     -d '{"ownerName":"Aram Hakobyan","currency":"AMD"}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

curl -s -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' \
     -H "X-Idempotency-Key: $(uuidgen)" \
     -d "{\"sourceAccountId\":\"$A\",\"targetAccountId\":\"$B\",\"amount\":\"15000.50\",\"currency\":\"AMD\"}"

curl -s localhost:8080/api/v1/transfers/<id>                    # ledger legs
curl -s localhost:8080/api/v1/receipts/<id>                      # {"url": "...", "expiresAt": "...", "sha256": "..."}
curl -s -o receipt.pdf "localhost:8080/api/v1/receipts/<id>?mode=stream"
curl -s "localhost:8080/api/v1/accounts/$A/statement?size=20"    # account activity + debit/credit totals
```

Errors are RFC 7807 `application/problem+json` with a stable `code`:
`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_ACTIVE`, `CURRENCY_MISMATCH`, `SAME_ACCOUNT`, `CBA_LIMIT_EXCEEDED`,
`IDEMPOTENCY_KEY_REUSED` (422), `ACCOUNT_NOT_FOUND`/`TRANSFER_NOT_FOUND` (404); receipt-service: `RECEIPT_NOT_FOUND` (404), `RECEIPT_NOT_READY` (409),
`LOCK_TIMEOUT` (503 + `Retry-After`), `VALIDATION_FAILED`/`MISSING_HEADER` (400).

## Design

```
ledger-service:  Controller → Service (transaction boundary) → Repository → PostgreSQL
                                  └─ same transaction: INSERT outbox_events
                 OutboxRelay (scheduled) → Kafka ledger.transfer.completed   (at-least-once)

receipt-service: @KafkaListener → INSERT receipts ON CONFLICT DO NOTHING → PDF → MinIO → UPDATE receipts
                 retryPending (scheduled, claims rows optimistically) ← FAILED / PENDING rows
```

**Money path – one DB transaction** (`TransferService.execute`):
1. `INSERT ... ON CONFLICT DO NOTHING` the idempotency key. 
2. `lock_timeout` (config `ledger.lock-timeout`, default 5s, set first so it also covers the idempotency wait), then `SELECT ... FOR UPDATE` on the two accounts **in ascending id order**.
3. Validate (status, currency, CBA ceiling, overdraft) in the `Account` aggregate; mutate balances.
4. Insert the transfer + DEBIT and CREDIT ledger rows; store the response body with the idempotency key.
5. The `transfer-completed` event is written to the `outbox_events` table in this same transaction, then commit.
   The event exists if and only if the transfer committed; a relay publishes it to Kafka afterwards.

### Decisions and trade-offs

* **Lock order, not retries, prevents deadlocks.** A→B and B→A both lock `min(id)` first. Covered by
  `oppositeDirectionTransfersDoNotDeadlockAndConserveMoney`. READ COMMITTED is enough *because* every
  read-modify-write happens under a row lock; SERIALIZABLE would add retry loops for no extra safety here.
* **Pessimistic locking only; no `@Version` on `Account`.** Optimistic locking detects a conflicting write after
  the fact and makes the caller retry. Here every balance change takes `SELECT ... FOR UPDATE` first, so a second
  writer waits and then reads the committed balance: there is never a stale read for `@Version` to catch, and the
  `OptimisticLockException` path (extra column, migration, error mapping, retry) would be dead code. Mixing both
  on one row also makes the concurrency story harder to reason about. Revisit if an account is ever updated
  without the row lock (e.g. an admin endpoint that freezes an account): read it with `findByIdForUpdate` too, or
  add `@Version` at that point.
* **Idempotency lives in the same transaction as the transfer.** A concurrent duplicate blocks on the unique
  index until the first request commits, then replays its stored response – exactly-once without advisory locks
  or a separate "in progress" state. Trade-off: a *failed* request (e.g. insufficient funds) rolls back its key,
  so a retry re-evaluates against current balances instead of replaying the error. Same key + different payload
  → 422 (payload hash comparison). A duplicate that waits on an in-flight original longer than `lock-timeout`
  gets 503 `LOCK_TIMEOUT` + `Retry-After`; retrying with the same key is always safe and then replays the result.
* **Opening balances are ledger postings** from a per-currency bank settlement account (seeded in V1), so
  "no balance mutation without ledger entries" holds from the first cent, and `SUM(balance) = 0` globally.
  Trade-off: every funded account creation contends on one settlement row per currency – fine for onboarding,
  would be sharded (N settlement accounts) at high volume. The API refuses settlement accounts as a transfer source.
* **Defence in depth in the schema:** `CHECK (balance >= -overdraft_limit)`, positive amounts, `source <> target`,
  and `LedgerEntry` is append-only in Java (no setters, every column `updatable = false`, `@PreRemove` rejects deletes). A bulk JPQL/SQL
  statement would bypass this - restrict the app DB role to INSERT/SELECT on `ledger_entries` in production.
* **Receipts are a separate service, outside the money transaction.** PDF + S3 I/O must not hold row locks or
  fail a transfer, and the receipt service has its own database schema (`receipt`) and deploys independently.
  The ledger announces every posting with a **self-contained** `ledger.transfer.completed` event (IBANs, owner
  names, amount, currency, timestamp, `schemaVersion`; key = transferId), so the consumer never reads ledger
  tables. The contract lives in the `ledger-events` module. The notification and statement services are further
  consumers of the same topic, each with its own consumer group, added without any ledger change.
* **Transactional outbox instead of "send after commit".** A best-effort send after commit can lose the event
  (broker down, crash between commit and send). The event is therefore inserted into `outbox_events` in the
  transfer's own transaction and published by `OutboxRelay` (`FOR UPDATE SKIP LOCKED`, so replicas cooperate)
  with acknowledgement before it is marked published. Delivery is at-least-once; during a broker outage events
  wait in the table and are delivered afterwards (covered by `TransferIT`).
* **Idempotent consumer.** The receipt service inserts the row with `ON CONFLICT DO NOTHING` and never
  regenerates a `READY` receipt, so redelivery is harmless. Malformed messages go to `ledger.transfer.completed.DLT`
  without retries. If MinIO is down the receipt stays `FAILED/PENDING` and a scheduler retries (max 5 attempts);
  several instances are safe because a row is claimed with an optimistic `UPDATE ... WHERE updated_at = :seen`.
* **Existing receipts were imported** from `transfers.receipt_*` by the receipt service's `V2` migration (a no-op
  on a fresh database). The now-unused `receipt_*` columns remain on `transfers` and can be dropped in a later
  ledger migration, once every environment has run the import.
* **Hashes:** `receipts.details_sha256` = SHA-256 of the canonical transaction string, printed in the PDF
  (re-computable from the ledger). `receipts.pdf_sha256` = digest of the stored file; the details hash is also S3
  object metadata. Object key is deterministic (`receipts/{transferId}.pdf`) and never rewritten once `READY`.
* **Bucket is private**; downloads only via pre-signed URLs signed for `MINIO_PUBLIC_ENDPOINT` (the host is part
  of the SigV4 signature, so it must be the address the client uses, not the in-network `minio:9000`).
  The task's compose snippet sets `mc anonymous set download`, which would expose every receipt – deliberately omitted.

### Known limitations (would address next)
* PDFs use built-in Helvetica (Latin-1); Armenian/Cyrillic owner names print as `?` until a Unicode font is embedded.
  Hashes are computed over the real (Unicode) values.
* No authN/Z, rate limiting, or per-customer daily CBA limits; a single configurable per-transfer ceiling stands in
  for CBA compliance.
* True object immutability needs MinIO Object Lock (WORM) on the bucket; here immutability is by key discipline + hashes.
* A MinIO failure during `GET /receipts/{id}?mode=stream` surfaces as 502 `STORAGE_UNAVAILABLE`; a circuit breaker would be next.
* The outbox relay polls (500 ms): events reach Kafka within about a second, not instantly. Receipt-service and the
  ledger share one Postgres server (separate schemas); a separate server per service is a configuration change.

## Stress testing concurrency

With the stack up:

```bash
./scripts/stress.sh http://localhost:8080 32
```

It fires 100 parallel 25.00 debits against a 1000.00 account (exactly 40 succeed, 60 get 422, balances must be
0.00 / 1000.00) and 20 parallel retries of a single idempotency key (one transfer id, balance debited once).
The same invariants are asserted in `TransferIT` (`concurrentDebitsNeverDoubleSpend`,
`concurrentRetriesWithSameKeyExecuteExactlyOnce`, `ledgerIsGloballyBalanced`).

To profile locks while stressing:

```sql
SELECT pid, wait_event_type, wait_event, state, left(query, 80) FROM pg_stat_activity WHERE datname = 'mainbank_db';
SELECT * FROM pg_locks WHERE NOT granted;
```

## Layout

```
pom.xml                  parent POM (mainbank-platform), modules below
ledger-events/           event contract shared by producer and consumers (plain types, no Spring)
  com/mainbank/ledger/events/TransferCompletedMessage
ledger-service/          the ledger microservice (accounts, transfers, double-entry ledger)
  src/main/java/com/mainbank/ledger
    domain/entity/     JPA entities with behaviour (Account.debit/credit)
    domain/constant/   enums (Currency, AccountStatus, TransferType, ...)
    domain/exception/  DomainException (code only, no HTTP knowledge; web layer maps ErrorCode -> status)
    repository/        Spring Data repos (FOR UPDATE query, idempotency claim)
    service/           TransferService (idempotency, limits), LedgerPostingService (locks + ledger posting), TransferLookupService, AccountService
    messaging/         TransferEventProducer (writes the outbox), OutboxRelay (publishes to Kafka)
    util/              Iban, Hashing, SystemAccounts
    api/               controllers
    dto/               request/response records
    exception/         GlobalExceptionHandler (ErrorCode -> HTTP status)
    config/            typed properties, scheduling
  src/main/resources/db/migration/   V1 schema, V2 seed, V3 outbox
receipt-service/         the receipt microservice: Kafka consumer, PDF, MinIO, receipt API
  src/main/java/com/mainbank/receipt
    messaging/         TransferCompletedConsumer (@KafkaListener, DLT error handler)
    service/           ReceiptService (idempotent generate + retry), ReceiptPdfGenerator, ReceiptStorage, ReceiptQueryService
    storage/           MinioClient + SigV4 (JDK-only S3 client)
    domain/, repository/  Receipt entity and repository (own `receipt` schema)
    api/, exception/   GET /api/v1/receipts/{transferId}, problem+json errors
  src/main/resources/db/migration/   V1 receipts table, V2 one-off import from the ledger
notification-service/    consumer: com/mainbank/notification  (NotificationService, NotificationSender, NotificationLog)
statement-service/       consumer: com/mainbank/statement      (StatementService, StatementRepository, StatementController)
api-gateway/             Spring Cloud Gateway: routes only (application.yml), no business code
```

Build everything with `mvn verify` from the repository root. One root `Dockerfile` builds any service image:
`docker build --build-arg MODULE=receipt-service --build-arg PORT=8081 .` (compose passes these for you).
Only the gateway is published on the host; each container has a memory limit and its JVM heap is 55% of it
(`JAVA_OPTS`, which overrides the Dockerfile defaults).
