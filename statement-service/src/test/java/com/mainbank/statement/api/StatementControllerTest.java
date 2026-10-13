package com.mainbank.statement.api;

import com.mainbank.statement.dto.StatementLine;
import com.mainbank.statement.dto.StatementResponse;
import com.mainbank.statement.service.StatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StatementControllerTest {

    @Mock StatementService statements;

    private MockMvc mvc;
    private final UUID account = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StatementController(statements))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void returnsTheStatementWithDefaultsAndPeriod() throws Exception {
        UUID transfer = UUID.randomUUID();
        var line = new StatementLine(transfer, Instant.parse("2026-01-02T03:04:05Z"), "DEBIT", "TRANSFER",
                new BigDecimal("25.50"), "USD", UUID.randomUUID(), "AM22", "Bob");
        when(statements.statement(eq(account), eq(Instant.parse("2026-01-01T00:00:00Z")), eq(null), eq(0), eq(50)))
                .thenReturn(new StatementResponse(account, Instant.parse("2026-01-01T00:00:00Z"), null, 0, 50, 1,
                        new BigDecimal("25.50"), BigDecimal.ZERO, List.of(line)));

        mvc.perform(get("/api/v1/accounts/{id}/statement?from=2026-01-01T00:00:00Z", account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLines").value(1))
                .andExpect(jsonPath("$.totalDebits").value(25.50))
                .andExpect(jsonPath("$.lines[0].counterpartyOwner").value("Bob"))
                .andExpect(jsonPath("$.lines[0].entryType").value("DEBIT"));
    }

    @Test
    void invalidPagingIs400WithAStableCode() throws Exception {
        when(statements.statement(any(), any(), any(), eq(0), eq(1000)))
                .thenThrow(new IllegalArgumentException("size must be between 1 and 200"));

        mvc.perform(get("/api/v1/accounts/{id}/statement?size=1000", account))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void malformedDateIs400() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}/statement?from=yesterday", account))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
}
