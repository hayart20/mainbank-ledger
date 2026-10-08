package com.mainbank.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mainbank.ledger.config.LedgerProperties;
import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.constant.ReceiptStatus;
import com.mainbank.ledger.domain.constant.TransferStatus;
import com.mainbank.ledger.domain.constant.TransferType;
import com.mainbank.ledger.domain.entity.IdempotencyRecord;
import com.mainbank.ledger.domain.entity.Transfer;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.dto.TransferRequest;
import com.mainbank.ledger.dto.TransferResponse;
import com.mainbank.ledger.event.TransferCompletedEvent;
import com.mainbank.ledger.repository.IdempotencyRepository;
import com.mainbank.ledger.repository.LedgerEntryRepository;
import com.mainbank.ledger.util.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Business flow of {@link TransferService} in isolation: idempotency, the CBA limit and event publishing. */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final BigDecimal LIMIT = new BigDecimal("1000.00");

    @Mock LedgerPostingService posting;
    @Mock LedgerEntryRepository ledger;
    @Mock IdempotencyRepository idempotency;
    @Mock ApplicationEventPublisher events;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final UUID key = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    private TransferService service;

    @BeforeEach
    void setUp() {
        var props = new LedgerProperties(LIMIT, Duration.ofSeconds(5),
                new LedgerProperties.Receipt(5, Duration.ofSeconds(30), Duration.ofSeconds(20)));
        service = new TransferService(posting, ledger, idempotency, events, json, props);
    }

    private TransferRequest request(String amount) {
        return new TransferRequest(source, target, new BigDecimal(amount), Currency.AMD);
    }

    private Transfer completedTransfer(String amount) {
        return Transfer.completed(key, TransferType.TRANSFER, source, target, new BigDecimal(amount), Currency.AMD);
    }

    @Test
    void firstRequestPostsPublishesReceiptEventAndCachesResponse() {
        TransferRequest request = request("250.25");
        Transfer transfer = completedTransfer("250.25");
        when(idempotency.tryClaim(key, Hashing.sha256Hex(request.canonical()))).thenReturn(1);
        when(posting.post(key, TransferType.TRANSFER, source, target, request.amount(), Currency.AMD, false))
                .thenReturn(transfer);
        when(ledger.findByTransferIdOrderByIdAsc(transfer.getId())).thenReturn(List.of());

        TransferService.Result result = service.execute(key, request);

        assertThat(result.status()).isEqualTo(TransferService.CREATED);
        assertThat(result.replayed()).isFalse();
        assertThat(result.body().id()).isEqualTo(transfer.getId());
        assertThat(result.body().amount()).isEqualByComparingTo("250.25");

        verify(events).publishEvent(new TransferCompletedEvent(transfer.getId()));
        ArgumentCaptor<String> cached = ArgumentCaptor.forClass(String.class);
        verify(idempotency).complete(eq(key), eq(transfer.getId()), eq(TransferService.CREATED), cached.capture());
        assertThat(cached.getValue()).contains(transfer.getId().toString());
    }

    @Test
    void lockTimeoutIsAppliedBeforeTheIdempotencyClaim() {
        TransferRequest request = request("10.00");
        Transfer transfer = completedTransfer("10.00");
        when(idempotency.tryClaim(any(), anyString())).thenReturn(1);
        when(posting.post(any(), any(), any(), any(), any(), any(), eq(false))).thenReturn(transfer);

        service.execute(key, request);

        InOrder order = inOrder(posting, idempotency);
        order.verify(posting).applyLockTimeout();
        order.verify(idempotency).tryClaim(eq(key), anyString());
        order.verify(posting).post(any(), any(), any(), any(), any(), any(), eq(false));
    }

    @Test
    void duplicateKeyWithSamePayloadReplaysStoredResponseWithoutPosting() throws Exception {
        TransferRequest request = request("40.00");
        String hash = Hashing.sha256Hex(request.canonical());
        UUID transferId = UUID.randomUUID();
        TransferResponse original = new TransferResponse(transferId, TransferType.TRANSFER, TransferStatus.COMPLETED,
                source, target, new BigDecimal("40.00"), Currency.AMD, Instant.now(), ReceiptStatus.PENDING, null, List.of());

        IdempotencyRecord record = mock(IdempotencyRecord.class);
        when(record.getRequestHash()).thenReturn(hash);
        when(record.getResponseStatus()).thenReturn(TransferService.CREATED);
        when(record.getResponseBody()).thenReturn(json.writeValueAsString(original));
        when(idempotency.tryClaim(key, hash)).thenReturn(0);
        when(idempotency.findById(key)).thenReturn(Optional.of(record));

        TransferService.Result result = service.execute(key, request);

        assertThat(result.replayed()).isTrue();
        assertThat(result.status()).isEqualTo(TransferService.CREATED);
        assertThat(result.body().id()).isEqualTo(transferId);
        verify(posting, never()).post(any(), any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(events);
        verify(idempotency, never()).complete(any(), any(), anyInt(), anyString());
    }

    @Test
    void duplicateKeyWithDifferentPayloadIsRejected() {
        TransferRequest request = request("20.00");
        IdempotencyRecord record = mock(IdempotencyRecord.class);
        when(record.getRequestHash()).thenReturn(Hashing.sha256Hex(request("10.00").canonical()));
        when(idempotency.tryClaim(eq(key), anyString())).thenReturn(0);
        when(idempotency.findById(key)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.execute(key, request))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        verify(posting, never()).post(any(), any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(events);
    }

    @Test
    void amountAboveSingleTransferLimitIsRejectedBeforeAnyMoneyMoves() {
        when(idempotency.tryClaim(any(), anyString())).thenReturn(1);

        assertThatThrownBy(() -> service.execute(key, request("1000.01")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CBA_LIMIT_EXCEEDED));
        verify(posting, never()).post(any(), any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(events);
    }

    @Test
    void amountExactlyAtTheLimitIsAllowed() {
        Transfer transfer = completedTransfer("1000.00");
        when(idempotency.tryClaim(any(), anyString())).thenReturn(1);
        when(posting.post(any(), any(), any(), any(), any(), any(), eq(false))).thenReturn(transfer);

        TransferService.Result result = service.execute(key, request("1000.00"));

        assertThat(result.status()).isEqualTo(TransferService.CREATED);
    }

    @Test
    void postingFailurePropagatesWithoutEventOrCachedResponse() {
        when(idempotency.tryClaim(any(), anyString())).thenReturn(1);
        when(posting.post(any(), any(), any(), any(), any(), any(), eq(false)))
                .thenThrow(new DomainException(ErrorCode.INSUFFICIENT_FUNDS, "no money"));

        assertThatThrownBy(() -> service.execute(key, request("10.00")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        verifyNoInteractions(events);
        verify(idempotency, never()).complete(any(), any(), anyInt(), anyString());
    }
}
