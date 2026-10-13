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

**Prerequisites:** Docker with Compose v2 (about 6 GB of memory for Docker is comfortable: the five services are
limited to 0.5–1 GB each). A local JDK 25 and Maven are only needed to build or test outside Docker.

```bash
docker compose up --build          # postgres + minio + bucket + kafka + 4 services + api-gateway, all on :8080
```

The first build downloads dependencies (a few minutes); later starts take about a minute. When
`docker compose ps` shows every container `healthy`, open **http://localhost:8080/swagger-ui.html**.

| Port (host) | What |
|---|---|
| `8080` | **api-gateway**: the only application entry point (API + Swagger UI) |
| `9000` / `9001` | MinIO S3 API (pre-signed receipt URLs point here) / console (`minioadmin` / `minioadminpassword`) |
| `5432` | PostgreSQL, db `mainbank_db`, user `asb_app` (schemas `public`, `receipt`, `notification`, `statement`) |
| `29092` | Kafka, for tools on the host (`kafka:9092` inside the compose network) |

The services themselves (ledger `8080`, receipt `8081`, notification `8082`, statement `8083`) are not published;
reach them through the gateway, or read their logs: `docker compose logs -f notification-service`.

```bash
docker compose down                # stop, keep the data (Postgres, MinIO and Kafka volumes)
docker compose down -v             # stop and wipe all data: use this for a clean start
docker compose up -d --build <service>   # rebuild and restart a single service, e.g. receipt-service
```

### Tests

```bash
mvn test                           # unit tests only (*Test, Surefire) for all modules - fast, no Docker needed
mvn verify                         # unit tests, then Testcontainers integration tests (*IT, Failsafe) - needs a Docker daemon
mvn -pl receipt-service -am verify # a single service (and what it depends on)
```

Unit and integration tests are separate: classes named `*Test` run in `mvn test`; classes named `*IT` (e.g.
`TransferIT`) run only in `mvn verify`, so `mvn test` stays green on a machine without Docker.

Integration tests use Testcontainers with real infrastructure: `ledger-service` runs against Postgres + Kafka
(concurrency, idempotency, outbox, broker outage); `receipt-service` runs against Postgres + Kafka + **MinIO**
(`ReceiptIT`: event → PDF in MinIO → pre-signed download, tampered URL rejected, redelivery, MinIO outage with
retry, poison message to the DLT).

Without a local JDK 25, run the same build inside a JDK 25 container (mounting the Docker socket so Testcontainers
can start its containers):

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/w" -v "$HOME/.m2:/root/.m2" \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /w maven:3.9-eclipse-temurin-25 mvn verify
```

> **MinIO images.** The image named in the task (`minio/minio:RELEASE.2023-09-04...`) can no longer be pulled
> (Docker Hub and Quay both refuse it), so compose defaults to `cgr.dev/chainguard/minio` and `minio-client`.
> Override with `MINIO_IMAGE` / `MINIO_MC_IMAGE` if you have a mirror. Those images have no `curl`, hence the
> bash `/dev/tcp` health probe. The bucket is created by `minio-init` **and** by the app if missing.
>
> **Docker Engine 29+.** docker-java defaults to API 1.32, which the engine rejects; the failsafe config pins
> `api.version=1.41` for the integration-test JVM.

### Troubleshooting

| Symptom | Cause and fix |
|---|---|
| A service exits with code 137 | Out of memory (`docker inspect <name>` shows `OOMKilled=true`). Give Docker Desktop more memory; every service has a `mem_limit` and a heap of 55% of it (`JAVA_OPTS` in `docker-compose.yml`). |
| `Schema-validation: missing column` / `Schema … has a version newer than the latest available migration` | An old Postgres volume from a different version of the project. `docker compose down -v`, then start again. |
| Port 5432, 9000 or 8080 already in use | Stop the local service using it, or change the host side of the mapping in `docker-compose.yml`. |
| Receipt link opens but MinIO answers 403 | The URL is signed for `MINIO_PUBLIC_ENDPOINT` (`http://localhost:9000`); open it through that exact host and port, and within 15 minutes. |
| `409 RECEIPT_NOT_READY` right after a transfer | Normal: receipts are produced asynchronously (about a second). Call again. |
| Gateway answers `503 SERVICE_UNAVAILABLE` (with `Retry-After`) | The service behind the route is down or still starting; check `docker compose ps`. |
| Gateway answers `504 UPSTREAM_TIMEOUT` | The service accepted the request but did not answer within 30 s (read timeout); check its logs. |

## API

### Swagger UI / OpenAPI

Start the stack (`docker compose up --build`) and open **http://localhost:8080/swagger-ui.html**.

| URL | What |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI for the whole system. The dropdown (top right) switches between the services |
| http://localhost:8080/docs/ledger/v3/api-docs | OpenAPI 3 spec (JSON) of **ledger-service**: accounts, transfers |
| http://localhost:8080/docs/receipt/v3/api-docs | OpenAPI spec of **receipt-service**: receipts |
| http://localhost:8080/docs/statement/v3/api-docs | OpenAPI spec of **statement-service**: account statements |

The specs can be imported into Postman, Insomnia or a client generator (`openapi-generator`).
`notification-service` has no HTTP API (it only consumes Kafka events), so it has no entry.

**Trying the API in the UI**

1. Pick **ledger-service** and run `POST /api/v1/accounts` twice (a funded payer, e.g. `initialBalance: 1000`, and an
   empty payee). Copy the two `id` values from the responses.
2. Run `POST /api/v1/transfers`. The header **`X-Idempotency-Key`** is required: paste any UUID. Sending the same
   key and body again replays the first response (`Idempotent-Replayed: true`) and moves no money.
3. Switch the dropdown to **receipt-service** and run `GET /api/v1/receipts/{transferId}`; use `mode=url` for a
   15-minute pre-signed MinIO link or `mode=stream` for the PDF. The receipt is produced asynchronously, so for
   about a second after the transfer it answers `409 RECEIPT_NOT_READY`; call it again.
4. Switch to **statement-service** and run `GET /api/v1/accounts/{accountId}/statement` for either account.

**How it works.** Each service generates its own spec with springdoc. The gateway exposes the three specs under
`/docs/<service>/v3/api-docs` (it strips the prefix and keeps the client's `Host` header, so each spec's `servers`
entry is `http://localhost:8080`) and serves one Swagger UI that lists them. "Try it out" therefore calls the
gateway, exactly like a real client, never an internal service address. The only service published on the host is
the gateway, so there is no other Swagger URL to use.

> The docs are open (like the rest of the API, which has no authentication yet). Before exposing the gateway
> publicly, put the UI and the specs behind authentication or disable them with
> `springdoc.swagger-ui.enabled=false` / `springdoc.api-docs.enabled=false`.

### Endpoints

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

curl -s localhost:8080/api/v1/transfers/<id>                    # ledger legs (the id from the POST response)
curl -s localhost:8080/api/v1/receipts/<id>                      # {"url": "...", "expiresAt": "...", "sha256": "..."}
curl -s -o receipt.pdf "localhost:8080/api/v1/receipts/<id>?mode=stream"
curl -s "localhost:8080/api/v1/accounts/$A/statement?size=20"    # account activity + debit/credit totals
```

(`uuidgen` is available on macOS and Linux; on Windows PowerShell use `[guid]::NewGuid()` for the idempotency
key, or just paste any UUID.)

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
  tables.
* **No shared code between services.** The only coupling is the event contract on the topic. The ledger owns
  and publishes the format (`ledger-service/.../messaging/TransferCompletedMessage`, guarded by a test on the
  exact field names); every consumer keeps its **own** record with just the fields it uses and ignores the rest
  (`@JsonIgnoreProperties(ignoreUnknown = true)`). The ledger can therefore add fields without touching or
  redeploying any consumer. The rules: add fields, never rename, repurpose or remove one; a breaking change
  needs a new `schemaVersion` and a transition period. Each consumer has a test that parses the ledger's JSON
  as published (plus an unknown future field), so drift shows up in the consumer's own build. The price is a
  few lines of duplication per service, which is the usual trade-off for independent deployability.
  The notification and statement services are further consumers of the same topic, each with its own consumer
  group, added without any ledger change.
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
* **The gateway is a plain servlet application, not a reactive one.** Only the edge proxies requests; the business
  services are Spring MVC with blocking JDBC, so a second programming model (Reactor `Mono`/`Flux`, WebFlux) would
  buy nothing here and cost a different mental model and error handling. The gateway therefore uses *Spring Cloud
  Gateway Server MVC* on Tomcat with **virtual threads** (`spring.threads.virtual.enabled`): a proxied call blocks
  a cheap virtual thread instead of a pool thread. Failures are mapped by one small `HandlerExceptionResolver`:
  an unreachable service (not listening, unknown host, connect failure) is **503** + `Retry-After`, a service that
  accepted the request but did not answer within the 30 s read timeout is **504**; everything else keeps Spring's
  normal handling. Connect timeout is 2 s, so a stopped service fails in about 2 s.
* **Bucket is private**; downloads only via pre-signed URLs signed for `MINIO_PUBLIC_ENDPOINT` (the host is part
  of the SigV4 signature, so it must be the address the client uses, not the in-network `minio:9000`).
  The task's compose snippet sets `mc anonymous set download`, which would expose every receipt – deliberately omitted.

### Known limitations (would address next)

**Security and operations**
* **No authentication or authorization anywhere.** Any client can read any account's statement or receipt, and the
  Swagger UI and specs are open. Authentication belongs at the gateway.
* No rate limiting (the gateway supports a Bucket4j `rateLimit` filter; not enabled). A single configurable
  per-transfer ceiling stands in for per-customer CBA limits.
* All services connect to Postgres as the same user (`asb_app`) and could read each other's schemas. One user per
  service, limited to its own schema, is the proper boundary.
* Compose credentials are development defaults committed in `docker-compose.yml` and `application.yml`.
* Single Kafka broker, replication factor 1, one Postgres: a development topology.

**Messaging**
* Delivery is at-least-once and consumers are idempotent, but there is no tooling to replay the dead-letter topic;
  records in `ledger.transfer.completed.DLT` are inspected and re-published by hand.
* The outbox relay polls every 500 ms, so events reach Kafka within about a second, not instantly.
* The `KafkaConfig` (DLT and retry policy) is repeated in each consumer; it could become a small internal library.
* `transfers.receipt_*` columns are unused since receipts moved to their own service; drop them in a later ledger
  migration, once every environment has run the receipt import.

**Features**
* PDFs use built-in Helvetica (Latin-1); Armenian/Cyrillic owner names print as `?` until a Unicode font is embedded.
  Hashes are computed over the real (Unicode) values.
* Notifications are only logged (`LoggingNotificationSender`); an e-mail, SMS or push sender plugs in behind the
  `NotificationSender` interface.
* Statements list movements and period totals but not a running balance (the event carries no balance), and deep
  paging is capped (`page * size` ≤ 100,000): narrow the period with `from`/`to` instead.
* True object immutability needs MinIO Object Lock (WORM) on the bucket; here immutability is by key discipline + hashes.
* A MinIO failure during `GET /receipts/{id}?mode=stream` surfaces as 502 `STORAGE_UNAVAILABLE`; a circuit breaker
  would be next.
* Receipt-service and the ledger share one Postgres server (separate schemas); a separate server per service is a
  configuration change.

## Stress testing concurrency

There is no separate stress script: the concurrency guarantees are asserted by integration tests that run real
parallel requests against Postgres (`mvn -pl ledger-service -am verify`):

| Test (`TransferIT`) | What it proves |
|---|---|
| `concurrentDebitsNeverDoubleSpend` | many parallel debits against one account: exactly as many succeed as the balance allows, the rest get 422, the balance never goes below the limit |
| `oppositeDirectionTransfersDoNotDeadlockAndConserveMoney` | A→B and B→A in parallel complete without deadlock and the total is conserved |
| `concurrentRetriesWithSameKeyExecuteExactlyOnce` | parallel retries of one idempotency key create one transfer; the balance is debited once |
| `ledgerIsGloballyBalanced` | the sum of debits equals the sum of credits, and the sum of all balances is zero |

To watch locks yourself while sending parallel requests to the running stack:

```sql
SELECT pid, wait_event_type, wait_event, state, left(query, 80) FROM pg_stat_activity WHERE datname = 'mainbank_db';
SELECT * FROM pg_locks WHERE NOT granted;
```

(`docker exec -it asb-postgres psql -U asb_app -d mainbank_db`)

## Layout

```
pom.xml                  parent POM (mainbank-platform), modules below
Dockerfile               one Dockerfile for every service (MODULE / PORT build args)
docker-compose.yml       postgres, minio, kafka, the five services; healthchecks and memory limits
.dockerignore            keeps target/ and .git out of the image build context
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
api-gateway/             Spring Cloud Gateway Server MVC (servlet, virtual threads): routes in application.yml, 503/504 mapping, no business code
```

Build everything with `mvn verify` from the repository root. One root `Dockerfile` builds any service image:
`docker build --build-arg MODULE=receipt-service --build-arg PORT=8081 .` (compose passes these for you).
Only the gateway is published on the host; each container has a memory limit and its JVM heap is 55% of it
(`JAVA_OPTS`, which overrides the Dockerfile defaults).
