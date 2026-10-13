package com.mainbank.ledger.repository;

import com.mainbank.ledger.domain.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /** {@code SELECT ... FOR UPDATE}. Must be called inside a transaction, in ascending id order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Next value of the sequence that feeds the account-number part of the IBAN. */
    @Query(value = "select nextval('iban_account_seq')", nativeQuery = true)
    long nextIbanSequence();
}
