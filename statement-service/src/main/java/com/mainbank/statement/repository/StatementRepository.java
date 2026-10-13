package com.mainbank.statement.repository;

import com.mainbank.statement.dto.StatementLine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model: one row per (transfer, account). All SQL is parameterised; only fixed fragments are concatenated. */
@Repository
public class StatementRepository {

    private final JdbcClient jdbc;

    public StatementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: a redelivered event finds the row and changes nothing. */
    public int insertIfAbsent(UUID transferId, UUID accountId, String entryType, String transferType, Instant occurredAt,
                              BigDecimal amount, String currency,
                              UUID counterpartyId, String counterpartyIban, String counterpartyOwner) {
        return jdbc.sql("""
                        insert into statement_lines (transfer_id, account_id, entry_type, transfer_type, occurred_at,
                                                     amount, currency, counterparty_account_id, counterparty_iban,
                                                     counterparty_owner)
                        values (:transferId, :accountId, :entryType, :transferType, :occurredAt,
                                :amount, :currency, :counterpartyId, :counterpartyIban, :counterpartyOwner)
                        on conflict (transfer_id, account_id) do nothing""")
                .param("transferId", transferId).param("accountId", accountId)
                .param("entryType", entryType).param("transferType", transferType)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("amount", amount).param("currency", currency)
                .param("counterpartyId", counterpartyId).param("counterpartyIban", counterpartyIban)
                .param("counterpartyOwner", counterpartyOwner)
                .update();
    }

    public List<StatementLine> page(UUID accountId, Instant from, Instant to, int limit, long offset) {
        var spec = jdbc.sql("""
                        select transfer_id, occurred_at, entry_type, transfer_type, amount, currency,
                               counterparty_account_id, counterparty_iban, counterparty_owner
                          from statement_lines
                         where account_id = :accountId
                           and (cast(:from as timestamptz) is null or occurred_at >= cast(:from as timestamptz))
                           and (cast(:to as timestamptz) is null or occurred_at < cast(:to as timestamptz))
                         order by occurred_at desc, transfer_id
                         limit :limit offset :offset""")
                .param("accountId", accountId)
                .param("from", from == null ? null : Timestamp.from(from))
                .param("to", to == null ? null : Timestamp.from(to))
                .param("limit", limit).param("offset", offset);
        return spec.query((rs, i) -> new StatementLine(
                rs.getObject("transfer_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("entry_type"), rs.getString("transfer_type"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getObject("counterparty_account_id", UUID.class),
                rs.getString("counterparty_iban"), rs.getString("counterparty_owner"))).list();
    }

    /** Line count and debit/credit sums for the whole period, independent of paging. */
    public Totals totals(UUID accountId, Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) as n,
                               coalesce(sum(amount) filter (where entry_type = 'DEBIT'), 0)  as debits,
                               coalesce(sum(amount) filter (where entry_type = 'CREDIT'), 0) as credits
                          from statement_lines
                         where account_id = :accountId
                           and (cast(:from as timestamptz) is null or occurred_at >= cast(:from as timestamptz))
                           and (cast(:to as timestamptz) is null or occurred_at < cast(:to as timestamptz))""")
                .param("accountId", accountId)
                .param("from", from == null ? null : Timestamp.from(from))
                .param("to", to == null ? null : Timestamp.from(to))
                .query((rs, i) -> new Totals(rs.getLong("n"), rs.getBigDecimal("debits"), rs.getBigDecimal("credits")))
                .single();
    }

    public record Totals(long lines, BigDecimal debits, BigDecimal credits) { }
}
