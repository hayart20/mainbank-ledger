package com.mainbank.ledger.integration;

import com.mainbank.ledger.domain.entity.LedgerEntry;
import com.mainbank.ledger.repository.LedgerEntryRepository;
import com.mainbank.ledger.dto.AccountResponse;
import com.mainbank.ledger.dto.TransferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class TransferIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired TransactionTemplate tx;

    // ---------- helpers ----------

    private AccountResponse account(String currency, String balance, String overdraft) {
        ResponseEntity<AccountResponse> r = rest.postForEntity("/api/v1/accounts",
                Map.of("ownerName", "Test Owner", "currency", currency, "initialBalance", balance, "overdraftLimit", overdraft),
                AccountResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private AccountResponse account(String balance) {
        return account("AMD", balance, "0");
    }

    private BigDecimal balance(UUID id) {
        return rest.getForObject("/api/v1/accounts/" + id, AccountResponse.class).balance();
    }

    private <T> ResponseEntity<T> transfer(UUID key, UUID from, UUID to, String amount, String currency, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("X-Idempotency-Key", key.toString());
        Map<String, Object> body = Map.of("sourceAccountId", from, "targetAccountId", to, "amount", amount, "currency", currency);
        return rest.exchange("/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<TransferResponse> transfer(UUID from, UUID to, String amount) {
        return transfer(UUID.randomUUID(), from, to, amount, "AMD", TransferResponse.class);
    }

    private <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return task.call();
            }));
        }
        gate.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) results.add(f.get());
        pool.shutdown();
        return results;
    }

    // ---------- tests ----------

    @Test
    void transferMovesMoneyAndPostsBalancedLedgerLegs() {
        AccountResponse a = account("1000.00");
        AccountResponse b = account("0");

        ResponseEntity<TransferResponse> r = transfer(a.id(), b.id(), "250.25");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TransferResponse t = r.getBody();
        assertThat(t.ledgerEntries()).hasSize(2);
        assertThat(t.ledgerEntries()).extracting(l -> l.entryType().name()).containsExactly("DEBIT", "CREDIT");
        assertThat(t.ledgerEntries()).extracting(l -> l.amount()).allSatisfy(x -> assertThat(x).isEqualByComparingTo("250.25"));
        assertThat(balance(a.id())).isEqualByComparingTo("749.75");
        assertThat(balance(b.id())).isEqualByComparingTo("250.25");

        TransferResponse fetched = rest.getForObject("/api/v1/transfers/" + t.id(), TransferResponse.class);
        assertThat(fetched.id()).isEqualTo(t.id());
        assertThat(fetched.ledgerEntries()).hasSize(2);
    }

    @Test
    void openingBalanceIsBackedByLedgerEntries() {
        AccountResponse a = account("500.00");
        BigDecimal ledgerNet = jdbc.queryForObject("""
                select coalesce(sum(case entry_type when 'CREDIT' then amount else -amount end), 0)
                  from ledger_entries where account_id = ?""", BigDecimal.class, a.id());
        assertThat(ledgerNet).isEqualByComparingTo("500.00");
    }

    @Test
    void replayedIdempotencyKeyReturnsCachedResponseWithoutReExecuting() {
        AccountResponse a = account("100.00");
        AccountResponse b = account("0");
        UUID key = UUID.randomUUID();

        ResponseEntity<TransferResponse> first = transfer(key, a.id(), b.id(), "40.00", "AMD", TransferResponse.class);
        ResponseEntity<TransferResponse> second = transfer(key, a.id(), b.id(), "40.00", "AMD", TransferResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(balance(a.id())).isEqualByComparingTo("60.00");
        assertThat(jdbc.queryForObject("select count(*) from transfers where idempotency_key = ?", Integer.class, key)).isEqualTo(1);
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejected() {
        AccountResponse a = account("100.00");
        AccountResponse b = account("0");
        UUID key = UUID.randomUUID();
        transfer(key, a.id(), b.id(), "10.00", "AMD", TransferResponse.class);

        ResponseEntity<Map> r = transfer(key, a.id(), b.id(), "20.00", "AMD", Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(a.id())).isEqualByComparingTo("90.00");
    }

    @Test
    void concurrentRetriesWithSameKeyExecuteExactlyOnce() throws Exception {
        AccountResponse a = account("1000.00");
        AccountResponse b = account("0");
        UUID key = UUID.randomUUID();

        List<ResponseEntity<TransferResponse>> responses = runConcurrently(16,
                () -> transfer(key, a.id(), b.id(), "100.00", "AMD", TransferResponse.class));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses.stream().map(r -> r.getBody().id()).distinct()).hasSize(1);
        assertThat(balance(a.id())).isEqualByComparingTo("900.00");
        assertThat(jdbc.queryForObject("select count(*) from transfers where idempotency_key = ?", Integer.class, key)).isEqualTo(1);
    }

    @Test
    void concurrentDebitsNeverDoubleSpend() throws Exception {
        AccountResponse source = account("100.00");   // enough for exactly 10 x 10.00
        AccountResponse target = account("0");

        List<HttpStatus> statuses = runConcurrently(30, () -> {
            ResponseEntity<Map> r = transfer(UUID.randomUUID(), source.id(), target.id(), "10.00", "AMD", Map.class);
            return HttpStatus.valueOf(r.getStatusCode().value());
        });

        assertThat(statuses.stream().filter(s -> s == HttpStatus.CREATED).count()).isEqualTo(10);
        assertThat(statuses.stream().filter(s -> s == HttpStatus.UNPROCESSABLE_ENTITY).count()).isEqualTo(20);
        assertThat(balance(source.id())).isEqualByComparingTo("0.00");
        assertThat(balance(target.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        AccountResponse a = account("1000.00");
        AccountResponse b = account("1000.00");

        List<Integer> codes = runConcurrently(40, () -> {
            boolean forward = Math.random() < 0.5;
            return transfer(UUID.randomUUID(), forward ? a.id() : b.id(), forward ? b.id() : a.id(), "1.00", "AMD", Map.class)
                    .getStatusCode().value();
        });

        assertThat(codes).containsOnly(201);
        assertThat(balance(a.id()).add(balance(b.id()))).isEqualByComparingTo("2000.00");
    }

    @Test
    void overdraftLimitIsHonoured() {
        AccountResponse a = account("AMD", "10.00", "50.00");
        AccountResponse b = account("0");

        assertThat(transfer(a.id(), b.id(), "60.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(a.id())).isEqualByComparingTo("-50.00");
        assertThat(transfer(UUID.randomUUID(), a.id(), b.id(), "0.01", "AMD", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void businessRulesAreEnforced() {
        AccountResponse amd = account("100.00");
        AccountResponse usd = account("USD", "100.00", "0");
        AccountResponse frozen = account("100.00");
        jdbc.update("update accounts set status = 'FROZEN' where id = ?", frozen.id());

        ResponseEntity<Map> mismatch = transfer(UUID.randomUUID(), amd.id(), usd.id(), "1.00", "AMD", Map.class);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mismatch.getBody()).containsEntry("code", "CURRENCY_MISMATCH");

        ResponseEntity<Map> fromFrozen = transfer(UUID.randomUUID(), frozen.id(), amd.id(), "1.00", "AMD", Map.class);
        assertThat(fromFrozen.getBody()).containsEntry("code", "ACCOUNT_NOT_ACTIVE");

        ResponseEntity<Map> toFrozen = transfer(UUID.randomUUID(), amd.id(), frozen.id(), "1.00", "AMD", Map.class);
        assertThat(toFrozen.getBody()).containsEntry("code", "ACCOUNT_NOT_ACTIVE");

        ResponseEntity<Map> same = transfer(UUID.randomUUID(), amd.id(), amd.id(), "1.00", "AMD", Map.class);
        assertThat(same.getBody()).containsEntry("code", "SAME_ACCOUNT");

        ResponseEntity<Map> unknown = transfer(UUID.randomUUID(), amd.id(), UUID.randomUUID(), "1.00", "AMD", Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> overLimit = transfer(UUID.randomUUID(), amd.id(), usd.id(), "999999999999.00", "AMD", Map.class);
        assertThat(overLimit.getBody()).containsEntry("code", "CBA_LIMIT_EXCEEDED");

        // failed transfers must leave balances untouched and no trace in the ledger
        assertThat(balance(amd.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void requestValidation() {
        AccountResponse a = account("1.00");
        AccountResponse b = account("0");

        assertThat(transfer(null, a.id(), b.id(), "1.00", "AMD", Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer(UUID.randomUUID(), a.id(), b.id(), "-5", "AMD", Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer(UUID.randomUUID(), a.id(), b.id(), "1.005", "AMD", Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer(UUID.randomUUID(), a.id(), b.id(), "1.00", "GBP", Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> badAccount = rest.postForEntity("/api/v1/accounts",
                Map.of("ownerName", "", "currency", "AMD"), Map.class);
        assertThat(badAccount.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void completedTransferIsAnnouncedThroughTheOutboxAndRelayedToKafka() {
        AccountResponse a = account("100.00");
        AccountResponse b = account("0");
        TransferResponse t = transfer(a.id(), b.id(), "12.34").getBody();

        // written atomically with the transfer, carrying everything a consumer needs
        Map<String, Object> row = jdbc.queryForMap(
                "select topic, payload from outbox_events where aggregate_id = ?", t.id());
        assertThat(row.get("topic")).isEqualTo("ledger.transfer.completed");
        assertThat((String) row.get("payload"))
                .contains("\"transferId\":\"" + t.id() + "\"", "\"amount\":12.34", "\"currency\":\"AMD\"", "\"schemaVersion\":1");

        // the relay publishes it to the broker and marks it done, without any client action
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select published_at is not null from outbox_events where aggregate_id = ?",
                        Boolean.class, t.id())).isTrue());
    }

    @Test
    void transferSucceedsAndIsAnnouncedLaterWhileTheBrokerIsDown() {
        AccountResponse a = account("100.00");
        AccountResponse b = account("0");

        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        UUID transferId;
        try {
            ResponseEntity<TransferResponse> r = transfer(a.id(), b.id(), "5.00");
            // the money movement is independent of the broker
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            transferId = r.getBody().id();
            assertThat(balance(a.id())).isEqualByComparingTo("95.00");
            assertThat(jdbc.queryForObject("select published_at is null from outbox_events where aggregate_id = ?",
                    Boolean.class, transferId)).isTrue();
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        // broker is back: the event is not lost, the relay delivers it
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select published_at is not null from outbox_events where aggregate_id = ?",
                        Boolean.class, transferId)).isTrue());
    }

    @Test
    void ledgerIsGloballyBalanced() {
        AccountResponse a = account("300.00");
        AccountResponse b = account("0");
        transfer(a.id(), b.id(), "100.00");

        BigDecimal debits = jdbc.queryForObject("select coalesce(sum(amount),0) from ledger_entries where entry_type='DEBIT'", BigDecimal.class);
        BigDecimal credits = jdbc.queryForObject("select coalesce(sum(amount),0) from ledger_entries where entry_type='CREDIT'", BigDecimal.class);
        BigDecimal balances = jdbc.queryForObject("select coalesce(sum(balance),0) from accounts", BigDecimal.class);
        assertThat(debits).isEqualByComparingTo(credits);
        assertThat(balances).isEqualByComparingTo("0");
        // every account balance equals its ledger net movement
        Integer drift = jdbc.queryForObject("""
                select count(*) from accounts a
                 where a.balance <> coalesce((select sum(case entry_type when 'CREDIT' then amount else -amount end)
                                                from ledger_entries l where l.account_id = a.id), 0)""", Integer.class);
        assertThat(drift).isZero();
    }

    @Test
    void ledgerRowsAreImmutable() {
        AccountResponse a = account("10.00");
        LedgerEntry entry = ledgerEntries.findAll().stream()
                .filter(e -> e.getAccountId().equals(a.id())).findFirst().orElseThrow();

        assertThatThrownBy(() -> ledgerEntries.delete(entry))
                .hasRootCauseInstanceOf(IllegalStateException.class).hasMessageContaining("append-only");

        // columns are updatable=false: in-memory edits are never flushed to the database
        tx.executeWithoutResult(s -> {
            LedgerEntry managed = ledgerEntries.findById(entry.getId()).orElseThrow();
            ReflectionTestUtils.setField(managed, "amount", new BigDecimal("999.00"));
        });

        assertThat(ledgerEntries.findById(entry.getId()).orElseThrow().getAmount()).isEqualByComparingTo("10.00");
    }
}
