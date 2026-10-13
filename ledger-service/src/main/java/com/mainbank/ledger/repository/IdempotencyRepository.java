package com.mainbank.ledger.repository;

import com.mainbank.ledger.domain.entity.IdempotencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface IdempotencyRepository extends JpaRepository<IdempotencyEntity, UUID> {

    /**
     * Atomically "claims" an idempotency key for the current transaction: the first request to insert a key owns
     * it. The claim is a single {@code INSERT ... ON CONFLICT DO NOTHING}, so there is no check-then-insert race
     * between concurrent requests; the primary-key index on {@code idempotency_key} decides the winner.
     * <p>
     * Outcomes:
     * <ul>
     *   <li><b>1</b> - the key was new, this request owns it and must execute the transfer.</li>
     *   <li><b>0</b> - the key already exists from a committed request: do not execute, replay the stored
     *       response instead.</li>
     *   <li><b>blocks</b> - another transaction has inserted the same key but not committed yet. PostgreSQL
     *       makes this statement wait until that transaction ends (bounded by {@code lock_timeout}), then it
     *       returns 0 if the other one committed, or inserts the key (1) if the other one rolled back.</li>
     * </ul>
     * The row is written in the caller's transaction, so the claim and the transfer commit or roll back together.
     * {@code request_hash} (SHA-256 of the canonical request) is stored so a later request that reuses the key
     * with a different payload can be detected.
     *
     * @return 1 if the key was claimed by this call, 0 if it already existed
     */
    @Modifying
    @Query(value = """
            insert into idempotency_keys (idempotency_key, request_hash)
            values (:key, :hash) on conflict (idempotency_key) do nothing""", nativeQuery = true)
    int tryClaim(@Param("key") UUID key, @Param("hash") String hash);

    @Modifying
    @Query(value = """
            update idempotency_keys set transfer_id = :transferId, response_status = :status, response_body = :body
             where idempotency_key = :key""", nativeQuery = true)
    int complete(@Param("key") UUID key, @Param("transferId") UUID transferId,
                 @Param("status") int status, @Param("body") String body);
}
