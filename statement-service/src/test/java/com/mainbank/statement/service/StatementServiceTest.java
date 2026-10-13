package com.mainbank.statement.service;

import com.mainbank.ledger.events.TransferCompletedMessage;
import com.mainbank.statement.dto.StatementResponse;
import com.mainbank.statement.repository.StatementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementServiceTest {

    private static final Instant WHEN = Instant.parse("2026-01-02T03:04:05.123Z");

    @Mock StatementRepository lines;

    private StatementService service;
    private final UUID transferId = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new StatementService(lines);
    }

    @Test
    void recordsADebitForThePayerAndACreditForThePayeeWithTheCounterparty() {
        var m = new TransferCompletedMessage(1, transferId, "TRANSFER", WHEN, source, "AM11", "Anna",
                target, "AM22", "Bob", new BigDecimal("25.50"), "USD");

        service.onTransferCompleted(m);

        InOrder order = inOrder(lines);
        order.verify(lines).insertIfAbsent(transferId, source, "DEBIT", "TRANSFER", WHEN, new BigDecimal("25.50"),
                "USD", target, "AM22", "Bob");
        order.verify(lines).insertIfAbsent(transferId, target, "CREDIT", "TRANSFER", WHEN, new BigDecimal("25.50"),
                "USD", source, "AM11", "Anna");
    }

    @Test
    void statementCombinesPageAndPeriodTotals() {
        UUID account = UUID.randomUUID();
        when(lines.totals(account, null, null))
                .thenReturn(new StatementRepository.Totals(3, new BigDecimal("30.00"), new BigDecimal("100.00")));
        when(lines.page(account, null, null, 2, 2)).thenReturn(List.of());

        StatementResponse r = service.statement(account, null, null, 1, 2);

        assertThat(r.totalLines()).isEqualTo(3);
        assertThat(r.totalDebits()).isEqualByComparingTo("30.00");
        assertThat(r.totalCredits()).isEqualByComparingTo("100.00");
        assertThat(r.page()).isEqualTo(1);
        assertThat(r.size()).isEqualTo(2);
        verify(lines).page(account, null, null, 2, 2);
    }

    @Test
    void invalidPagingAndPeriodAreRejectedBeforeAnyQuery() {
        UUID account = UUID.randomUUID();

        assertThatThrownBy(() -> service.statement(account, null, null, -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.statement(account, null, null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.statement(account, null, null, 0, StatementService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.statement(account, WHEN, WHEN, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        // page * size overflows an int: must be a 400, not an ArithmeticException / 500
        assertThatThrownBy(() -> service.statement(account, null, null, Integer.MAX_VALUE, 200))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(lines);
    }
}
