package com.mainbank.statement.api;

import com.mainbank.statement.dto.StatementResponse;
import com.mainbank.statement.service.StatementService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/statement")
public class StatementController {

    private final StatementService statements;

    public StatementController(StatementService statements) {
        this.statements = statements;
    }

    /**
     * Account activity, newest first. {@code from} is inclusive and {@code to} exclusive (ISO-8601 instants);
     * an account without movements returns an empty page, not 404, because this service does not own accounts.
     */
    @GetMapping
    public StatementResponse statement(@PathVariable UUID accountId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return statements.statement(accountId, from, to, page, size);
    }
}
