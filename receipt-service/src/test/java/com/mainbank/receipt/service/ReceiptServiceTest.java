package com.mainbank.receipt.service;

import com.mainbank.receipt.messaging.TransferCompletedMessage;
import com.mainbank.receipt.config.ReceiptProperties;
import com.mainbank.receipt.domain.Receipt;
import com.mainbank.receipt.domain.ReceiptStatus;
import com.mainbank.receipt.repository.ReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Receipt pipeline without PDF/MinIO/DB: event handling, idempotence, failure bookkeeping and the retry claim. */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1'};
    private static final Instant WHEN = Instant.parse("2026-01-02T03:04:05.123Z");

    @Mock ReceiptRepository receipts;
    @Mock ReceiptPdfGenerator pdf;
    @Mock ReceiptStorage storage;

    private ReceiptService service;
    private final UUID id = UUID.randomUUID();
    private final TransferCompletedMessage message = new TransferCompletedMessage(id, "TRANSFER", WHEN,
            "AM11", "Anna", "AM22", "Bob", new BigDecimal("12.34"), "AMD");

    @BeforeEach
    void setUp() {
        service = new ReceiptService(receipts, pdf, storage,
                new ReceiptProperties(5, Duration.ofSeconds(30), Duration.ofSeconds(20)));
    }

    private Receipt receipt(ReceiptStatus status) {
        Receipt r = newInstance();
        ReflectionTestUtils.setField(r, "transferId", id);
        ReflectionTestUtils.setField(r, "type", "TRANSFER");
        ReflectionTestUtils.setField(r, "transferCreatedAt", WHEN);
        ReflectionTestUtils.setField(r, "sourceIban", "AM11");
        ReflectionTestUtils.setField(r, "sourceOwner", "Anna");
        ReflectionTestUtils.setField(r, "targetIban", "AM22");
        ReflectionTestUtils.setField(r, "targetOwner", "Bob");
        ReflectionTestUtils.setField(r, "amount", new BigDecimal("12.34"));
        ReflectionTestUtils.setField(r, "currency", "AMD");
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "updatedAt", WHEN);
        return r;
    }

    private static Receipt newInstance() {
        try {
            var c = Receipt.class.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void eventRecordsTheFactsThenStoresThePdfAndMarksReady() {
        when(receipts.findById(id)).thenReturn(Optional.of(receipt(ReceiptStatus.PENDING)));
        when(pdf.generate(any())).thenReturn(PDF);
        String key = "receipts/" + id + ".pdf";
        String detailsHash = ReceiptData.of(message).detailsHash();

        service.onTransferCompleted(message);

        verify(receipts).insertIfAbsent(id, "TRANSFER", WHEN, "AM11", "Anna", "AM22", "Bob", new BigDecimal("12.34"), "AMD");
        verify(storage).put(key, PDF, detailsHash);
        verify(receipts).markReady(eq(id), eq(key), eq(detailsHash), eq(ReceiptData.sha256Hex(PDF)), any());
        verify(receipts, never()).markFailed(any(), any());
    }

    @Test
    void redeliveryOfAReadyReceiptDoesNothingMore() {
        when(receipts.findById(id)).thenReturn(Optional.of(receipt(ReceiptStatus.READY)));

        service.onTransferCompleted(message);

        verifyNoInteractions(pdf, storage);
        verify(receipts, never()).markReady(any(), any(), any(), any(), any());
        verify(receipts, never()).markFailed(any(), any());
    }

    @Test
    void storageFailureIsRecordedAsFailedAndNeverThrownToTheListener() {
        when(receipts.findById(id)).thenReturn(Optional.of(receipt(ReceiptStatus.PENDING)));
        when(pdf.generate(any())).thenReturn(PDF);
        doThrow(new IllegalStateException("MinIO down")).when(storage).put(anyString(), any(), anyString());

        service.onTransferCompleted(message);

        verify(receipts).markFailed(eq(id), any());
        verify(receipts, never()).markReady(any(), any(), any(), any(), any());
    }

    @Test
    void pdfFailureIsRecordedAsFailedAndNothingIsUploaded() {
        when(receipts.findById(id)).thenReturn(Optional.of(receipt(ReceiptStatus.PENDING)));
        when(pdf.generate(any())).thenThrow(new IllegalStateException("PDF generation failed"));

        service.onTransferCompleted(message);

        verify(receipts).markFailed(eq(id), any());
        verifyNoInteractions(storage);
    }

    @Test
    void retryReprocessesOnlyReceiptsThisInstanceClaims() {
        UUID lost = UUID.randomUUID();
        Receipt mine = receipt(ReceiptStatus.FAILED);
        Receipt other = receipt(ReceiptStatus.FAILED);
        ReflectionTestUtils.setField(other, "transferId", lost);
        when(receipts.findRetryable(eq(5), any(Instant.class), any(Pageable.class))).thenReturn(List.of(mine, other));
        when(receipts.claim(eq(id), eq(WHEN), any())).thenReturn(1);
        when(receipts.claim(eq(lost), eq(WHEN), any())).thenReturn(0);
        when(receipts.findById(id)).thenReturn(Optional.of(mine));
        when(pdf.generate(any())).thenReturn(PDF);

        service.retryPending();

        ArgumentCaptor<UUID> processed = ArgumentCaptor.forClass(UUID.class);
        verify(receipts).findById(processed.capture());
        assertThat(processed.getValue()).isEqualTo(id);
        verify(storage).put(eq("receipts/" + id + ".pdf"), eq(PDF), anyString());
    }

    @Test
    void retryUsesTheConfiguredMaximumAttemptsAndMinimumAge() {
        service.retryPending();

        ArgumentCaptor<Instant> olderThan = ArgumentCaptor.forClass(Instant.class);
        verify(receipts).findRetryable(eq(5), olderThan.capture(), any(Pageable.class));
        assertThat(Duration.between(olderThan.getValue(), Instant.now())).isBetween(Duration.ofSeconds(19), Duration.ofSeconds(25));
    }
}
