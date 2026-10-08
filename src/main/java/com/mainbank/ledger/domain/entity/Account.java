package com.mainbank.ledger.domain.entity;

import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.constant.AccountStatus;
import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.exception.DomainException;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Bank account. It contains behaviour ({@link #debit}, {@link #credit}) instead of being a plain data holder
 * because the overdraft and active-status rules must hold on every balance change, whichever service triggers it.
 * With no {@code setBalance}, a new use case cannot skip them, and they are unit-testable without Spring or a DB.
 * Locking, ledger entries and transactions stay in the services; callers must hold a row lock
 * ({@code SELECT ... FOR UPDATE}) while changing the balance.
 */
@Getter
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String iban;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Currency currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "overdraft_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal overdraftLimit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "system_account", nullable = false, updatable = false)
    private boolean systemAccount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * No-arg constructor required by JPA/Hibernate, which instantiates entities reflectively when loading rows
     * and then populates the fields. It is {@code protected} so application code cannot create a half-initialised
     * account; use {@link #open} instead.
     */
    protected Account() { }

    /**
     * Opens a new account in a valid initial state: random id, zero balance, {@link AccountStatus#ACTIVE},
     * not a system account, and both timestamps set to now.
     * <p>
     * A static factory is used instead of a public constructor so that every new account starts with these
     * defaults and cannot be created with an arbitrary balance or status. Money can only enter the account
     * afterwards through {@link #credit}, backed by ledger entries. The overdraft limit is normalised to
     * 2 decimal places to match the {@code NUMERIC(19, 2)} column.
     *
     * @param iban           generated IBAN, unique across accounts
     * @param ownerName      account holder name
     * @param currency       fixed for the lifetime of the account
     * @param overdraftLimit non-negative amount the balance may go below zero
     */
    public static Account open(String iban, String ownerName, Currency currency, BigDecimal overdraftLimit) {
        Account a = new Account();
        a.id = UUID.randomUUID();
        a.iban = iban;
        a.ownerName = ownerName;
        a.currency = currency;
        a.balance = BigDecimal.ZERO.setScale(2);
        a.overdraftLimit = overdraftLimit.setScale(2);
        a.status = AccountStatus.ACTIVE;
        a.systemAccount = false;
        a.createdAt = a.updatedAt = Instant.now();
        return a;
    }

    /**
     * Withdraws {@code amount} from this account (the DEBIT leg of a transfer).
     * <p>
     * The account must be {@link AccountStatus#ACTIVE}, and the resulting balance may not fall below
     * {@code -overdraftLimit}: with a limit of 0 the account cannot go negative, with a limit of 50.00 it may
     * reach exactly -50.00 but not -50.01. The check and the balance update happen in one place so that every
     * debit, from any use case, applies the same rule and no caller can overdraw an account by forgetting it.
     * <p>
     * This method only changes the in-memory balance. The caller must hold the row lock
     * ({@code SELECT ... FOR UPDATE}), otherwise two concurrent debits could both pass the check and spend the
     * same money, and must write the matching ledger entry. If the rule is violated the balance is left unchanged.
     *
     * @param amount positive amount, already validated and scaled to 2 decimals by the caller
     * @throws DomainException {@code ACCOUNT_NOT_ACTIVE} if the account is frozen,
     *                         {@code INSUFFICIENT_FUNDS} if the overdraft limit would be exceeded
     */
    public void debit(BigDecimal amount) {
        requireActive();
        BigDecimal newBalance = balance.subtract(amount);
        if (newBalance.compareTo(overdraftLimit.negate()) < 0) {
            throw new DomainException(ErrorCode.INSUFFICIENT_FUNDS, "Account %s has insufficient funds for %s %s".formatted(iban, amount.toPlainString(), currency));
        }
        balance = newBalance;
        updatedAt = Instant.now();
    }

    /**
     * Adds {@code amount} to this account (the CREDIT leg of a transfer).
     * <p>
     * There is no upper bound on a credit, but the account must be {@link AccountStatus#ACTIVE}: a frozen
     * account is blocked in both directions, so it can neither pay out nor receive money until it is
     * {@link #activate() activated} again. The status check sits next to {@link #debit} and shares the same guard,
     * so both directions follow exactly the same rule.
     * <p>
     * Like {@link #debit}, this changes only the in-memory balance; the caller must hold the row lock and write
     * the matching ledger entry so that every balance change is backed by the double-entry ledger.
     *
     * @param amount positive amount, already validated and scaled to 2 decimals by the caller
     * @throws DomainException {@code ACCOUNT_NOT_ACTIVE} if the account is frozen
     */
    public void credit(BigDecimal amount) {
        requireActive();
        balance = balance.add(amount);
        updatedAt = Instant.now();
    }

    /**
     * Freezes the account, for example for compliance, suspected fraud or a court order.
     * <p>
     * While frozen, {@link #debit} and {@link #credit} are both rejected with {@code ACCOUNT_NOT_ACTIVE}, but the
     * balance and the ledger history stay untouched and readable. Freezing an already frozen account has no
     * further effect. Status changes go through this named method instead of a setter so that they are an explicit,
     * searchable operation in the code.
     */
    public void freeze() { status = AccountStatus.FROZEN; updatedAt = Instant.now(); }

    /**
     * Reverses {@link #freeze()}: the account becomes {@link AccountStatus#ACTIVE} and can be debited and
     * credited again, subject to the usual overdraft rule. The balance is not changed, and activating an account
     * that is already active has no further effect.
     */
    public void activate() { status = AccountStatus.ACTIVE; updatedAt = Instant.now(); }

    /**
     * Guard shared by {@link #debit} and {@link #credit}: lets the call proceed only if the account is
     * {@link AccountStatus#ACTIVE}, otherwise throws {@code ACCOUNT_NOT_ACTIVE} with the IBAN and current status.
     * <p>
     * It is a separate method so both directions apply exactly the same status rule and a future status (for
     * example CLOSED) is handled in one place. It is private because it is an internal precondition, not an
     * operation callers should invoke on their own; it is called before any balance change, so a rejected call
     * never leaves the account half-modified.
     *
     * @throws DomainException {@code ACCOUNT_NOT_ACTIVE} if the account is not active
     */
    private void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new DomainException(ErrorCode.ACCOUNT_NOT_ACTIVE, "Account " + iban + " is " + status);
        }
    }
}
