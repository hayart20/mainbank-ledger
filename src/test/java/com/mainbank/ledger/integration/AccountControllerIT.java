package com.mainbank.ledger.integration;

import com.mainbank.ledger.dto.AccountResponse;
import com.mainbank.ledger.util.Iban;
import com.mainbank.ledger.util.SystemAccounts;
import com.mainbank.ledger.domain.constant.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP contract of {@code /api/v1/accounts}: status codes, headers, validation errors and ledger side effects. */
class AccountControllerIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    private ResponseEntity<AccountResponse> create(Map<String, Object> body) {
        return rest.postForEntity("/api/v1/accounts", body, AccountResponse.class);
    }

    private ResponseEntity<Map> createExpectingError(Map<String, Object> body) {
        return rest.postForEntity("/api/v1/accounts", body, Map.class);
    }

    private BigDecimal settlementBalance(Currency currency) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class,
                SystemAccounts.settlementFor(currency));
    }

    // ---------- POST: success ----------

    @Test
    void createReturns201WithLocationAndAnEmptyActiveAccount() {
        ResponseEntity<AccountResponse> r = create(Map.of("ownerName", "Anna Hakobyan", "currency", "AMD"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AccountResponse a = r.getBody();
        assertThat(r.getHeaders().getLocation()).hasPath("/api/v1/accounts/" + a.id());
        assertThat(a.ownerName()).isEqualTo("Anna Hakobyan");
        assertThat(a.currency()).isEqualTo(Currency.AMD);
        assertThat(a.status().name()).isEqualTo("ACTIVE");
        assertThat(a.balance()).isEqualByComparingTo("0.00");
        assertThat(a.overdraftLimit()).isEqualByComparingTo("0.00");
        assertThat(a.updatedAt()).isNotNull();
        assertThat(Iban.isValid(a.iban())).isTrue();
        assertThat(a.iban()).startsWith("AM");
    }

    @Test
    void ownerNameIsTrimmed() {
        AccountResponse a = create(Map.of("ownerName", "   Anna   ", "currency", "USD")).getBody();

        assertThat(a.ownerName()).isEqualTo("Anna");
    }

    @Test
    void createWithOverdraftLimitStoresItWithTwoDecimals() {
        AccountResponse a = create(Map.of("ownerName", "Anna", "currency", "EUR", "overdraftLimit", "75.5")).getBody();

        assertThat(a.overdraftLimit()).isEqualByComparingTo("75.50");
        assertThat(a.balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void initialBalanceIsFundedFromTheSettlementAccountAsAnOpeningDeposit() {
        BigDecimal settlementBefore = settlementBalance(Currency.RUB);

        AccountResponse a = create(Map.of("ownerName", "Anna", "currency", "RUB", "initialBalance", "250.75")).getBody();

        assertThat(a.balance()).isEqualByComparingTo("250.75");
        // the money came from the bank's settlement account, not from nowhere
        assertThat(settlementBalance(Currency.RUB)).isEqualByComparingTo(settlementBefore.subtract(new BigDecimal("250.75")));
        // and is backed by exactly one OPENING_DEPOSIT transfer with a DEBIT and a CREDIT leg
        Map<String, Object> transfer = jdbc.queryForMap(
                "select id, type, source_account_id, amount from transfers where target_account_id = ?", a.id());
        assertThat(transfer.get("type")).isEqualTo("OPENING_DEPOSIT");
        assertThat(transfer.get("source_account_id")).isEqualTo(SystemAccounts.settlementFor(Currency.RUB));
        assertThat((BigDecimal) transfer.get("amount")).isEqualByComparingTo("250.75");
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where transfer_id = ?", Integer.class, transfer.get("id")))
                .isEqualTo(2);
    }

    @Test
    void zeroInitialBalanceCreatesNoTransferAtAll() {
        AccountResponse a = create(Map.of("ownerName", "Anna", "currency", "AMD", "initialBalance", "0")).getBody();

        assertThat(jdbc.queryForObject("select count(*) from transfers where target_account_id = ?", Integer.class, a.id())).isZero();
    }

    @Test
    void everyCreatedAccountGetsADifferentIban() {
        List<String> ibans = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ibans.add(create(Map.of("ownerName", "Anna", "currency", "AMD")).getBody().iban());
        }

        assertThat(ibans).doesNotHaveDuplicates();
    }

    @Test
    void concurrentCreationsAllSucceedWithUniqueIbansAndTheSettlementAccountStaysConsistent() throws Exception {
        int n = 20;
        BigDecimal before = settlementBalance(Currency.EUR);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ResponseEntity<AccountResponse>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return create(Map.of("ownerName", "Anna", "currency", "EUR", "initialBalance", "10.00"));
            }));
        }
        gate.countDown();
        List<AccountResponse> created = new ArrayList<>();
        for (Future<ResponseEntity<AccountResponse>> f : futures) {
            ResponseEntity<AccountResponse> r = f.get();
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            created.add(r.getBody());
        }
        pool.shutdown();

        assertThat(created).extracting(AccountResponse::iban).doesNotHaveDuplicates();
        assertThat(created).extracting(AccountResponse::balance).allSatisfy(b -> assertThat(b).isEqualByComparingTo("10.00"));
        // 20 deposits of 10.00 contended on one settlement row; none may be lost
        assertThat(settlementBalance(Currency.EUR)).isEqualByComparingTo(before.subtract(new BigDecimal("200.00")));
    }

    // ---------- POST: validation ----------

    @Test
    void invalidBodiesAreRejectedWith400AndNothingIsCreated() {
        int accountsBefore = jdbc.queryForObject("select count(*) from accounts", Integer.class);

        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("ownerName", "", "currency", "AMD"),
                Map.<String, Object>of("ownerName", "   ", "currency", "AMD"),
                Map.<String, Object>of("ownerName", "x".repeat(201), "currency", "AMD"),
                Map.<String, Object>of("ownerName", "Anna"),
                Map.<String, Object>of("ownerName", "Anna", "currency", "AMD", "initialBalance", "-1"),
                Map.<String, Object>of("ownerName", "Anna", "currency", "AMD", "initialBalance", "1.005"),
                Map.<String, Object>of("ownerName", "Anna", "currency", "AMD", "overdraftLimit", "-0.01"))) {
            ResponseEntity<Map> r = createExpectingError(body);
            assertThat(r.getStatusCode()).as(body.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).as(body.toString()).containsEntry("code", "VALIDATION_FAILED");
            assertThat((List<?>) r.getBody().get("errors")).as(body.toString()).isNotEmpty();
        }

        assertThat(jdbc.queryForObject("select count(*) from accounts", Integer.class)).isEqualTo(accountsBefore);
    }

    @Test
    void validationErrorNamesTheOffendingField() {
        ResponseEntity<Map> r = createExpectingError(Map.of("ownerName", "", "currency", "AMD"));

        assertThat((List<String>) r.getBody().get("errors")).anyMatch(e -> e.startsWith("ownerName:"));
    }

    @Test
    void unknownCurrencyIsRejectedAsMalformedRequest() {
        ResponseEntity<Map> r = createExpectingError(Map.of("ownerName", "Anna", "currency", "GBP"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "MALFORMED_REQUEST");
    }

    @Test
    void brokenJsonIsRejectedAsMalformedRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> r = rest.postForEntity("/api/v1/accounts", new HttpEntity<>("{\"ownerName\": ", headers), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "MALFORMED_REQUEST");
    }

    // ---------- GET ----------

    @Test
    void getReturnsTheStoredAccount() {
        AccountResponse created = create(Map.of("ownerName", "Anna", "currency", "AMD", "initialBalance", "40.00",
                "overdraftLimit", "10.00")).getBody();

        ResponseEntity<AccountResponse> r = rest.getForEntity("/api/v1/accounts/" + created.id(), AccountResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().id()).isEqualTo(created.id());
        assertThat(r.getBody().iban()).isEqualTo(created.iban());
        assertThat(r.getBody().balance()).isEqualByComparingTo("40.00");
        assertThat(r.getBody().overdraftLimit()).isEqualByComparingTo("10.00");
    }

    @Test
    void getReflectsAFrozenAccount() {
        AccountResponse created = create(Map.of("ownerName", "Anna", "currency", "AMD")).getBody();
        jdbc.update("update accounts set status = 'FROZEN' where id = ?", created.id());

        AccountResponse r = rest.getForObject("/api/v1/accounts/" + created.id(), AccountResponse.class);

        assertThat(r.status().name()).isEqualTo("FROZEN");
    }

    @Test
    void getUnknownAccountReturns404WithStableErrorCode() {
        ResponseEntity<Map> r = rest.getForEntity("/api/v1/accounts/" + UUID.randomUUID(), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("code", "ACCOUNT_NOT_FOUND");
    }

    @Test
    void getWithMalformedIdReturns400() {
        ResponseEntity<Map> r = rest.getForEntity("/api/v1/accounts/not-a-uuid", Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "INVALID_PARAMETER");
    }
}
