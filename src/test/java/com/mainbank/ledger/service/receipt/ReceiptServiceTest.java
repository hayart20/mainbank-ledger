package com.mainbank.ledger.service.receipt;

import com.mainbank.ledger.config.LedgerProperties;
import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.ReceiptStatus;
import com.mainbank.ledger.domain.constant.TransferType;
import com.mainbank.ledger.domain.entity.Account;
import com.mainbank.ledger.domain.entity.Transfer;
import com.mainbank.ledger.repository.AccountRepository;
import com.mainbank.ledger.repository.TransferRepository;
import com.mainbank.ledger.util.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Receipt pipeline without PDF/MinIO: success path, failure bookkeeping, idempotence and the retry query. */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1'};

    @Mock TransferRepository transfers;
    @Mock AccountRepository accounts;
    @Mock ReceiptPdfGenerator pdf;
    @Mock ReceiptStorage storage;

    private ReceiptService service;
    private Transfer transfer;
    private Account source;
    private Account target;

    @BeforeEach
    void setUp() {
        var props = new LedgerProperties(new BigDecimal("1000.00"), Duration.ofSeconds(5),
                new LedgerProperties.Receipt(5, Duration.ofSeconds(30), Duration.ofSeconds(20)));
        service = new ReceiptService(transfers, accounts, pdf, storage, props);

        source = Account.open("AM01", "Anna", Currency.AMD, BigDecimal.ZERO);
        target = Account.open("AM02", "Bob", Currency.AMD, BigDecimal.ZERO);
        transfer = Transfer.completed(UUID.randomUUID(), TransferType.TRANSFER, source.getId(), target.getId(),
                new BigDecimal("12.34"), Currency.AMD);
    }

    private void givenTransferAndAccounts() {
        when(transfers.findById(transfer.getId())).thenReturn(Optional.of(transfer));
        when(accounts.findById(source.getId())).thenReturn(Optional.of(source));
        when(accounts.findById(target.getId())).thenReturn(Optional.of(target));
    }

    // ---------- generateAndStore ----------

    @Test
    void successfulReceiptIsUploadedUnderDeterministicKeyAndMarkedReadyWithBothHashes() {
        givenTransferAndAccounts();
        when(pdf.generate(any())).thenReturn(PDF);
        String detailsHash = ReceiptData.of(transfer, source, target).detailsHash();
        String key = "receipts/" + transfer.getId() + ".pdf";

        service.generateAndStore(transfer.getId());

        verify(storage).put(key, PDF, detailsHash);
        verify(transfers).markReceiptReady(transfer.getId(), key, detailsHash, Hashing.sha256Hex(PDF));
        verify(transfers, never()).markReceiptFailed(any());
    }

    @Test
    void storageFailureIsRecordedAsFailedAndNeverThrownToTheCaller() {
        givenTransferAndAccounts();
        when(pdf.generate(any())).thenReturn(PDF);
        doThrow(new IllegalStateException("MinIO down")).when(storage).put(anyString(), any(), anyString());

        service.generateAndStore(transfer.getId());

        verify(transfers).markReceiptFailed(transfer.getId());
        verify(transfers, never()).markReceiptReady(any(), any(), any(), any());
    }

    @Test
    void pdfFailureIsRecordedAsFailedAndNothingIsUploaded() {
        givenTransferAndAccounts();
        when(pdf.generate(any())).thenThrow(new IllegalStateException("PDF generation failed"));

        service.generateAndStore(transfer.getId());

        verify(transfers).markReceiptFailed(transfer.getId());
        verifyNoInteractions(storage);
    }

    @Test
    void readyReceiptIsNeverRegenerated() {
        ReflectionTestUtils.setField(transfer, "receiptStatus", ReceiptStatus.READY);
        when(transfers.findById(transfer.getId())).thenReturn(Optional.of(transfer));

        service.generateAndStore(transfer.getId());

        verifyNoInteractions(pdf, storage, accounts);
        verify(transfers, never()).markReceiptFailed(any());
    }

    @Test
    void unknownTransferIsIgnored() {
        UUID id = UUID.randomUUID();
        when(transfers.findById(id)).thenReturn(Optional.empty());

        service.generateAndStore(id);

        verifyNoInteractions(pdf, storage, accounts);
        verify(transfers, never()).markReceiptFailed(any());
    }

    @Test
    void concurrentAttemptForTheSameTransferIsSkippedWhileOneIsInFlight() {
        givenTransferAndAccounts();
        // re-enter generateAndStore for the same id while the first call is still running (async listener vs retry job)
        when(pdf.generate(any())).thenAnswer(inv -> {
            service.generateAndStore(transfer.getId());
            return PDF;
        });

        service.generateAndStore(transfer.getId());

        verify(pdf, times(1)).generate(any());
        verify(transfers, times(1)).markReceiptReady(any(), any(), any(), any());
    }

    @Test
    void inFlightMarkerIsReleasedSoAFailedReceiptCanBeRetried() {
        givenTransferAndAccounts();
        when(pdf.generate(any())).thenReturn(PDF);
        doThrow(new IllegalStateException("MinIO down")).doNothing().when(storage).put(anyString(), any(), anyString());

        service.generateAndStore(transfer.getId());
        service.generateAndStore(transfer.getId());

        verify(transfers).markReceiptFailed(transfer.getId());
        verify(transfers).markReceiptReady(eq(transfer.getId()), anyString(), anyString(), anyString());
    }

    // ---------- retryPending ----------

    @Test
    void retryJobAsksForUnfinishedReceiptsOlderThanMinAgeAndRetriesEach() {
        UUID id = UUID.randomUUID();
        when(transfers.findReceiptsToRetry(eq(5), any(Instant.class), eq(PageRequest.of(0, 50)))).thenReturn(List.of(id));
        when(transfers.findById(id)).thenReturn(Optional.empty());
        Instant before = Instant.now();

        service.retryPending();

        ArgumentCaptor<Instant> olderThan = ArgumentCaptor.forClass(Instant.class);
        verify(transfers).findReceiptsToRetry(eq(5), olderThan.capture(), any());
        // cut-off is "now - retry-min-age (20s)", so a receipt that is still being generated is not picked up
        assertThat(olderThan.getValue()).isBetween(before.minusSeconds(21), Instant.now().minusSeconds(19));
        verify(transfers).findById(id);
    }
}
