package com.mainbank.ledger.domain.entity;

import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.exception.DomainException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountEntityTest
{

    private static AccountEntity account(String overdraft) {
        return AccountEntity.open("AM00TEST", "Test Owner", Currency.AMD, new BigDecimal(overdraft));
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void creditIncreasesBalance() {
        AccountEntity a = account("0");
        a.credit(d("100.50"));
        assertThat(a.getBalance()).isEqualByComparingTo("100.50");
    }

    @Test
    void debitBeyondBalanceIsRejectedWithoutOverdraft() {
        AccountEntity a = account("0");
        a.credit(d("10.00"));
        assertThatThrownBy(() -> a.debit(d("10.01")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(a.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void overdraftAllowsNegativeBalanceUpToLimitInclusive() {
        AccountEntity a = account("50.00");
        a.debit(d("50.00"));
        assertThat(a.getBalance()).isEqualByComparingTo("-50.00");
        assertThatThrownBy(() -> a.debit(d("0.01"))).isInstanceOf(DomainException.class);
    }

    @Test
    void frozenAccountRejectsDebitAndCredit() {
        AccountEntity a = account("0");
        a.credit(d("10.00"));
        a.freeze();
        assertThatThrownBy(() -> a.debit(d("1.00")))
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE);
        assertThatThrownBy(() -> a.credit(d("1.00")))
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE);
        a.activate();
        a.debit(d("1.00"));
        assertThat(a.getBalance()).isEqualByComparingTo("9.00");
    }
}
