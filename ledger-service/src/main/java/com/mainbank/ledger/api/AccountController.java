package com.mainbank.ledger.api;

import com.mainbank.ledger.service.AccountService;
import com.mainbank.ledger.dto.AccountResponse;
import com.mainbank.ledger.dto.CreateAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Open accounts and read their current balance")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @Operation(
            summary = "Open an account",
            description = """
                    Creates an ACTIVE account with a generated IBAN. A non-zero `initialBalance` is not written \
                    directly: it is posted as an OPENING_DEPOSIT from the bank's settlement account of the same \
                    currency, so it appears in the ledger and in the statement like any other movement.""")
    @ApiResponse(responseCode = "201", description = "Account created",
            headers = @Header(name = "Location", description = "URL of the new account"))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or MALFORMED_REQUEST (e.g. unknown currency)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse body = AccountResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + body.id())).body(body);
    }

    @Operation(summary = "Get an account", description = "Returns the account with its current balance.")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: id is not a valid UUID",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public AccountResponse get(@Parameter(description = "Account id") @PathVariable UUID id) {
        return AccountResponse.from(service.get(id));
    }
}
