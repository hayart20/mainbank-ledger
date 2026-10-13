package com.mainbank.ledger.service;

import com.mainbank.ledger.domain.constant.AccountStatus;
import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.entity.AccountEntity;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.dto.CreateAccountRequest;
import com.mainbank.ledger.repository.AccountRepository;
import com.mainbank.ledger.util.Iban;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountEntityServiceTest
{

    @Mock AccountRepository accounts;
    @Mock LedgerPostingService posting;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(accounts, posting);
    }

    private void givenIbanSequence() {
        when(accounts.nextIbanSequence()).thenReturn(1_000_000_042L);
        when(accounts.saveAndFlush(any(AccountEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void createOpensActiveAccountWithZeroBalanceAndGeneratedIban() {
        givenIbanSequence();

        AccountEntity a = service.create(new CreateAccountRequest("  Hayk Harutyunyan  ", Currency.USD, null, new BigDecimal("50")));

        assertThat(a.getOwnerName()).isEqualTo("Hayk Harutyunyan"); // trimmed
        assertThat(a.getCurrency()).isEqualTo(Currency.USD);
        assertThat(a.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(a.isSystemAccount()).isFalse();
        assertThat(a.getBalance()).isEqualByComparingTo("0.00");
        assertThat(a.getOverdraftLimit()).isEqualByComparingTo("50.00");
        assertThat(a.getIban()).isEqualTo(Iban.build(1_000_000_042L));
    }

    @Test
    void createWithoutInitialBalanceDoesNotPostAnOpeningDeposit() {
        givenIbanSequence();

        service.create(new CreateAccountRequest("Hayk", Currency.AMD, null, null));
        service.create(new CreateAccountRequest("Hayk", Currency.AMD, BigDecimal.ZERO, null));

        verify(posting, never()).openingDeposit(any(), any());
    }

    @Test
    void createWithInitialBalanceFundsItThroughALedgerPostingNeverADirectWrite() {
        givenIbanSequence();

        AccountEntity a = service.create(new CreateAccountRequest("Hayk", Currency.AMD, new BigDecimal("500.00"), null));

        verify(posting).openingDeposit(a, new BigDecimal("500.00"));
        assertThat(a.getBalance()).isEqualByComparingTo("0.00"); // only the (mocked) posting may change it
    }

    @Test
    void lockTimeoutIsAppliedBeforeAnythingIsWritten() {
        givenIbanSequence();

        service.create(new CreateAccountRequest("Hayk", Currency.AMD, new BigDecimal("1.00"), null));

        InOrder order = inOrder(posting, accounts);
        order.verify(posting).applyLockTimeout();
        order.verify(accounts).saveAndFlush(any(AccountEntity.class));
        order.verify(posting).openingDeposit(any(), any());
    }

    @Test
    void getReturnsTheAccount() {
        AccountEntity a = AccountEntity.open("AM01", "Hayk", Currency.AMD, BigDecimal.ZERO);
        when(accounts.findById(a.getId())).thenReturn(Optional.of(a));

        assertThat(service.get(a.getId())).isSameAs(a);
    }

    @Test
    void getUnknownAccountFailsWithAccountNotFound() {
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
