# Mainbank Ledger

Multi-currency double-entry ledger with ACID transfers, DB-backed idempotency and asynchronous,
hash-stamped PDF receipts in MinIO, triggered through Kafka.
Spring Boot 3.3 · Java 21 · PostgreSQL 15 · Flyway · Kafka · AWS SDK v2 · Testcontainers.

## Run

```bash
docker compose up --build          # postgres + minio + bucket + kafka + app on :8080
mvn test                           # unit tests only (*Test, Surefire) - fast, no Docker needed
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
| GET | `/api/v1/transfers/{id}` | status, ledger legs, receipt status + pre-signed URL when ready |
| GET | `/api/v1/transfers/{id}/receipt` | `?mode=url` (default, 15 min pre-signed URL) or `?mode=stream` (PDF piped) |

```bash
A=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
     -d '{"ownerName":"Anna Petrosyan","currency":"AMD","initialBalance":1000000}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
B=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
     -d '{"ownerName":"Aram Hakobyan","currency":"AMD"}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

curl -s -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' \
     -H "X-Idempotency-Key: $(uuidgen)" \
     -d "{\"sourceAccountId\":\"$A\",\"targetAccountId\":\"$B\",\"amount\":\"15000.50\",\"currency\":\"AMD\"}"

curl -s localhost:8080/api/v1/transfers/<id>                    # ledger legs + receipt status
curl -s localhost:8080/api/v1/transfers/<id>/receipt            # {"url": "...", "expiresAt": "...", "sha256": "..."}
curl -s -o receipt.pdf "localhost:8080/api/v1/transfers/<id>/receipt?mode=stream"
```

Errors are RFC 7807 `application/problem+json` with a stable `code`:
`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_ACTIVE`, `CURRENCY_MISMATCH`, `SAME_ACCOUNT`, `CBA_LIMIT_EXCEEDED`,
`IDEMPOTENCY_KEY_REUSED` (422), `ACCOUNT_NOT_FOUND`/`TRANSFER_NOT_FOUND` (404), `RECEIPT_NOT_READY` (409),
`LOCK_TIMEOUT` (503 + `Retry-After`), `VALIDATION_FAILED`/`MISSING_HEADER` (400).

## Design

```
Controller → Service (transaction boundary) → Repository → PostgreSQL
                 └─ AFTER_COMMIT event → ReceiptService (async pool) → PDF → MinIO → UPDATE transfers
                                         ReceiptService.retryPending (scheduled) ← FAILED / PENDING rows
```

**Money path – one DB transaction** (`TransferService.execute`):
1. `INSERT ... ON CONFLICT DO NOTHING` the idempotency key. 
2. `lock_timeout` (config `ledger.lock-timeout`, default 5s, set first so it also covers the idempotency wait), then `SELECT ... FOR UPDATE` on the two accounts **in ascending id order**.
3. Validate (status, currency, CBA ceiling, overdraft) in the `Account` aggregate; mutate balances.
4. Insert the transfer + DEBIT and CREDIT ledger rows; store the response body with the idempotency key.
5. Commit. Only then is the receipt event delivered.

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
* **Receipts are outside the money transaction.** PDF + S3 I/O must not hold row locks or fail a transfer. The
  trigger is a Kafka message (`ledger.transfer.completed`, key = transferId) that is sent only **after the
  transfer transaction commits** (best effort: a failed send is logged, never fails the transfer). A consumer in
  group `mainbank-ledger-receipts` generates the receipt; malformed messages go to `ledger.transfer.completed.DLT`.
  If Kafka or MinIO is down, a message is lost, or the JVM dies between commit and upload, the row stays
  `PENDING/FAILED` and a scheduler retries (max 5 attempts, then it stays `FAILED` for ops – visible in the DB and
  in `GET /transfers/{id}`). Consumers are idempotent, so redelivery is harmless.
* **Hashes:** `receipt_sha256` = SHA-256 of the canonical transaction string, printed in the PDF (re-computable
  from DB columns). `receipt_pdf_sha256` = digest of the stored file; the details hash is also S3 object metadata.
  Object key is deterministic (`receipts/{transferId}.pdf`) and never rewritten once `READY`.
* **Bucket is private**; downloads only via pre-signed URLs signed for `MINIO_PUBLIC_ENDPOINT` (the host is part
  of the SigV4 signature, so it must be the address the client uses, not the in-network `minio:9000`).
  The task's compose snippet sets `mc anonymous set download`, which would expose every receipt – deliberately omitted.

### Known limitations (would address next)
* Retry job and async listener are de-duplicated with an in-JVM set; with several app instances use
  `SELECT ... FOR UPDATE SKIP LOCKED` / a claim column.
* PDFs use built-in Helvetica (Latin-1); Armenian/Cyrillic owner names print as `?` until a Unicode font is embedded.
  Hashes are computed over the real (Unicode) values.
* No authN/Z, rate limiting, or per-customer daily CBA limits; a single configurable per-transfer ceiling stands in
  for CBA compliance.
* True object immutability needs MinIO Object Lock (WORM) on the bucket; here immutability is by key discipline + hashes.
* Failures of MinIO during `GET .../receipt?mode=stream` surface as 500; a circuit breaker would be next.

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
src/main/java/com/mainbank/ledger
  domain/entity/     JPA entities with behaviour (Account.debit/credit)
  domain/constant/   enums (Currency, AccountStatus, TransferType, ...)
  domain/exception/  DomainException (code only, no HTTP knowledge; web layer maps ErrorCode -> status)
  repository/  Spring Data repos (FOR UPDATE query, idempotency claim)
  service/           TransferService (idempotency, limits), LedgerPostingService (locks + ledger posting), TransferLookupService, AccountService
  service/receipt/   ReceiptService (async + retry), ReceiptPdfGenerator, ReceiptStorage (MinIO), ReceiptData
  event/             TransferCompletedEvent
  util/              Iban, Hashing, SystemAccounts
  api/         controllers
  dto/         request/response records
  exception/   GlobalExceptionHandler (ErrorCode -> HTTP status)
  config/      S3 clients, async pool, typed properties
src/main/resources/db/migration/V1__ledger_schema.sql
```
