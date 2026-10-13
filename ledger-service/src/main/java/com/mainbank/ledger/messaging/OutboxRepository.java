package com.mainbank.ledger.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Locks a batch of unpublished events, oldest first. {@code skip locked} lets several ledger instances relay
     * concurrently without publishing the same row twice or blocking each other.
     */
    @Query(nativeQuery = true, value = """
            select * from outbox_events
             where published_at is null
             order by id
             limit :limit
               for update skip locked""")
    List<OutboxEvent> lockBatch(@Param("limit") int limit);

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
