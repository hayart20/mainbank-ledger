package com.mainbank.ledger.service;

import com.mainbank.ledger.util.Iban;

import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.entity.AccountEntity;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.repository.AccountRepository;
import com.mainbank.ledger.dto.CreateAccountRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerPostingService posting;

    public AccountService(AccountRepository accounts, LedgerPostingService posting) {
        this.accounts = accounts;
        this.posting = posting;
    }

    @Transactional
    public AccountEntity create(CreateAccountRequest request) {
        posting.applyLockTimeout();
        AccountEntity accountEntity = accounts.saveAndFlush(AccountEntity.open(Iban.build(accounts.nextIbanSequence()),
                request.ownerName().trim(), request.currency(), request.overdraftLimitOrZero()));

        // No direct balance writes: the opening balance is a real ledger posting against the settlement account.
        if (request.initialBalanceOrZero().signum() > 0) {
            posting.openingDeposit(accountEntity, request.initialBalanceOrZero());
        }
        return accountEntity;
    }

    @Transactional(readOnly = true)
    public AccountEntity get(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new DomainException(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + id + " not found"));
    }
}
