package com.mainbank.ledger.service;

import com.mainbank.ledger.config.LedgerProperties;
import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.EntryType;
import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.constant.TransferType;
import com.mainbank.ledger.domain.entity.Account;
import com.mainbank.ledger.domain.entity.LedgerEntry;
import com.mainbank.ledger.domain.entity.Transfer;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.messaging.TransferEventProducer;
import com.mainbank.ledger.repository.AccountRepository;
import com.mainbank.ledger.repository.LedgerEntryRepository;
import com.mainbank.ledger.repository.TransferRepository;
import com.mainbank.ledger.util.SystemAccounts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The money-moving core without a database: lock order, validation order and double-entry output. */
@ExtendWith(MockitoExtension.class)
class LedgerPostingServiceTest {

    // Ascending order matters for the lock-order tests; positive most-significant bits keep UUID.compareTo intuitive.
    private static final UUID LOW = UUID.fromString("10000000-0000-0000-0000-000000000000");
    private static final UUID HIGH = UUID.fromString("20000000-0000-0000-0000-000000000000");

    @Mock AccountRepository accounts;
    @Mock TransferRepository transfers;
    @Mock LedgerEntryRepository ledger;
    @Mock TransferEventProducer events;
    @Mock EntityManager em;

    private LedgerPostingService service;

    @BeforeEach
    void setUp() {
        var props = new LedgerProperties(new BigDecimal("1000.00"), Duration.ofSeconds(5),
                new LedgerProperties.Receipt(5, Duration.ofSeconds(30), Duration.ofSeconds(20)));
        service = new LedgerPostingService(accounts, transfers, ledger, events, em, props);
    }

    private static Account account(UUID id, Currency currency, String balance, String overdraft) {
        Account a = Account.open("AM" + id.toString().substring(0, 8), "Owner " + id.toString().charAt(0), currency,
                new BigDecimal(overdraft));
        ReflectionTestUtils.setField(a, "id", id);
        ReflectionTestUtils.setField(a, "balance", new BigDecimal(balance));
        return a;
    }

    private void givenLocked(Account... locked) {
        for (Account a : locked) {
            when(accounts.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
        }
    }

    private Transfer post(UUID source, UUID target, String amount, Currency currency, boolean allowSystemSource) {
        return service.post(UUID.randomUUID(), TransferType.TRANSFER, source, target, new BigDecimal(amount), currency, allowSystemSource);
    }

    // ---------- applyLockTimeout ----------

    @Test
    void lockTimeoutIsSetLocallyFromConfiguration() {
        Query query = org.mockito.Mockito.mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("timeout", "5000ms")).thenReturn(query);

        service.applyLockTimeout();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        // third argument "true" = transaction-local, i.e. SET LOCAL semantics
        assertThat(sql.getValue()).contains("set_config('lock_timeout'").contains("true");
        verify(query).getSingleResult();
    }

    // ---------- post: locking ----------

    @ParameterizedTest(name = "source has lower id = {0}")
    @ValueSource(booleans = {true, false})
    void accountsAreAlwaysLockedInAscendingIdOrder(boolean sourceHasLowerId) {
        Account low = account(LOW, Currency.AMD, "100.00", "0");
        Account high = account(HIGH, Currency.AMD, "100.00", "0");
        givenLocked(low, high);
        when(transfers.save(any())).thenAnswer(i -> i.getArgument(0));

        if (sourceHasLowerId) post(LOW, HIGH, "10.00", Currency.AMD, false);
        else post(HIGH, LOW, "10.00", Currency.AMD, false);

        InOrder order = inOrder(accounts);
        order.verify(accounts).findByIdForUpdate(LOW);
        order.verify(accounts).findByIdForUpdate(HIGH);
    }

    @ParameterizedTest(name = "source has lower id = {0}")
    @ValueSource(booleans = {true, false})
    void lockedRowsAreMappedBackToTheirRolesWhateverTheLockOrder(boolean sourceHasLowerId) {
        Account low = account(LOW, Currency.AMD, "100.00", "0");
        Account high = account(HIGH, Currency.AMD, "100.00", "0");
        givenLocked(low, high);
        when(transfers.save(any())).thenAnswer(i -> i.getArgument(0));

        Account source = sourceHasLowerId ? low : high;
        Account target = sourceHasLowerId ? high : low;
        post(source.getId(), target.getId(), "30.00", Currency.AMD, false);

        assertThat(source.getBalance()).isEqualByComparingTo("70.00");
        assertThat(target.getBalance()).isEqualByComparingTo("130.00");
    }

    // ---------- post: result ----------

    @Test
    void postWritesCompletedTransferWithBalancedDebitAndCreditLegs() {
        Account source = account(LOW, Currency.AMD, "100.00", "0");
        Account target = account(HIGH, Currency.AMD, "0.00", "0");
        givenLocked(source, target);
        when(transfers.save(any())).thenAnswer(i -> i.getArgument(0));
        UUID key = UUID.randomUUID();

        Transfer t = service.post(key, TransferType.TRANSFER, LOW, HIGH, new BigDecimal("12.3"), Currency.AMD, false);

        assertThat(t.getIdempotencyKey()).isEqualTo(key);
        assertThat(t.getAmount()).isEqualTo(new BigDecimal("12.30")); // normalised to 2 decimals
        assertThat(t.getSourceAccountId()).isEqualTo(LOW);
        assertThat(t.getTargetAccountId()).isEqualTo(HIGH);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> legs = ArgumentCaptor.forClass(List.class);
        verify(ledger).saveAll(legs.capture());
        assertThat(legs.getValue()).hasSize(2);
        assertThat(legs.getValue()).extracting(LedgerEntry::getEntryType).containsExactly(EntryType.DEBIT, EntryType.CREDIT);
        assertThat(legs.getValue()).extracting(LedgerEntry::getAccountId).containsExactly(LOW, HIGH);
        assertThat(legs.getValue()).extracting(LedgerEntry::getAmount).allSatisfy(a -> assertThat(a).isEqualTo(new BigDecimal("12.30")));
        // flushed so DB constraints fire inside the transaction, not at commit
        verify(accounts).flush();
    }

    // ---------- post: validation ----------

    @Test
    void sameSourceAndTargetIsRejectedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> post(LOW, LOW, "1.00", Currency.AMD, false))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SAME_ACCOUNT));
        verifyNoInteractions(accounts, transfers, ledger);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00"})
    void nonPositiveAmountIsRejectedBeforeTouchingTheDatabase(String amount) {
        assertThatThrownBy(() -> post(LOW, HIGH, amount, Currency.AMD, false))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_AMOUNT));
        verifyNoInteractions(accounts, transfers, ledger);
    }

    @Test
    void unknownAccountIsReportedAsNotFound() {
        when(accounts.findByIdForUpdate(LOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> post(LOW, HIGH, "1.00", Currency.AMD, false))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        verify(transfers, never()).save(any());
    }

    @Test
    void settlementAccountCannotBeDebitedUnlessExplicitlyAllowed() {
        Account settlement = account(LOW, Currency.AMD, "0.00", "1000000.00");
        ReflectionTestUtils.setField(settlement, "systemAccount", true);
        Account customer = account(HIGH, Currency.AMD, "0.00", "0");
        givenLocked(settlement, customer);

        assertThatThrownBy(() -> post(LOW, HIGH, "1.00", Currency.AMD, false))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED));
        assertThat(settlement.getBalance()).isEqualByComparingTo("0.00");
        verify(transfers, never()).save(any());
    }

    @Test
    void currencyMustMatchBothAccounts() {
        Account amd = account(LOW, Currency.AMD, "100.00", "0");
        Account usd = account(HIGH, Currency.USD, "100.00", "0");
        givenLocked(amd, usd);

        assertThatThrownBy(() -> post(LOW, HIGH, "1.00", Currency.AMD, false))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
        assertThat(amd.getBalance()).isEqualByComparingTo("100.00");
        assertThat(usd.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void insufficientFundsLeavesNoTransferAndNoLedgerRows() {
        Account source = account(LOW, Currency.AMD, "5.00", "0");
        Account target = account(HIGH, Currency.AMD, "0.00", "0");
        givenLocked(source, target);

        assertThatThrownBy(() -> post(LOW, HIGH, "5.01", Currency.AMD, false))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        assertThat(source.getBalance()).isEqualByComparingTo("5.00");
        assertThat(target.getBalance()).isEqualByComparingTo("0.00");
        verify(transfers, never()).save(any());
        verify(ledger, never()).saveAll(any());
    }

    // ---------- openingDeposit ----------

    @Test
    void openingDepositDebitsTheSettlementAccountAndPublishesReceiptEvent() {
        UUID settlementId = SystemAccounts.settlementFor(Currency.AMD);
        Account settlement = account(settlementId, Currency.AMD, "0.00", "1000000.00");
        ReflectionTestUtils.setField(settlement, "systemAccount", true);
        Account customer = account(HIGH, Currency.AMD, "0.00", "0");
        givenLocked(settlement, customer);
        when(transfers.save(any())).thenAnswer(i -> i.getArgument(0));

        Transfer t = service.openingDeposit(customer, new BigDecimal("500.00"));

        assertThat(t.getType()).isEqualTo(TransferType.OPENING_DEPOSIT);
        assertThat(t.getSourceAccountId()).isEqualTo(settlementId);
        assertThat(customer.getBalance()).isEqualByComparingTo("500.00");
        assertThat(settlement.getBalance()).isEqualByComparingTo("-500.00");
        verify(events).publishAfterCommit(t.getId());
    }
}
