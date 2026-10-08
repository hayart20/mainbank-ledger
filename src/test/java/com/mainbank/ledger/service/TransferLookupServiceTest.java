package com.mainbank.ledger.service;

import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.constant.ReceiptStatus;
import com.mainbank.ledger.domain.constant.TransferType;
import com.mainbank.ledger.domain.entity.Transfer;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.dto.ReceiptLinkResponse;
import com.mainbank.ledger.dto.TransferResponse;
import com.mainbank.ledger.repository.LedgerEntryRepository;
import com.mainbank.ledger.repository.TransferRepository;
import com.mainbank.ledger.service.receipt.ReceiptStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferLookupServiceTest {

    @Mock TransferRepository transfers;
    @Mock LedgerEntryRepository ledger;
    @Mock ReceiptStorage storage;

    private TransferLookupService service;

    @BeforeEach
    void setUp() {
        service = new TransferLookupService(transfers, ledger, storage);
    }

    private Transfer transfer(ReceiptStatus receiptStatus) {
        Transfer t = Transfer.completed(UUID.randomUUID(), TransferType.TRANSFER, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), Currency.AMD);
        ReflectionTestUtils.setField(t, "receiptStatus", receiptStatus);
        if (receiptStatus == ReceiptStatus.READY) {
            ReflectionTestUtils.setField(t, "receiptObjectKey", "receipts/" + t.getId() + ".pdf");
            ReflectionTestUtils.setField(t, "receiptSha256", "a".repeat(64));
        }
        when(transfers.findById(t.getId())).thenReturn(Optional.of(t));
        return t;
    }

    private static DomainException codeOf(Throwable e) {
        assertThat(e).isInstanceOf(DomainException.class);
        return (DomainException) e;
    }

    // ---------- get ----------

    @Test
    void getIncludesPresignedUrlOnlyWhenReceiptIsReady() {
        Transfer t = transfer(ReceiptStatus.READY);
        when(ledger.findByTransferIdOrderByIdAsc(t.getId())).thenReturn(List.of());
        when(storage.presignDownload(t.getReceiptObjectKey(), "receipt-" + t.getId() + ".pdf"))
                .thenReturn(new ReceiptStorage.Link("http://minio/signed", Instant.now()));

        TransferResponse r = service.get(t.getId());

        assertThat(r.id()).isEqualTo(t.getId());
        assertThat(r.receiptStatus()).isEqualTo(ReceiptStatus.READY);
        assertThat(r.receiptUrl()).isEqualTo("http://minio/signed");
    }

    @ParameterizedTest
    @EnumSource(value = ReceiptStatus.class, names = {"PENDING", "FAILED"})
    void getHasNoUrlAndNeverTouchesStorageWhileReceiptIsNotReady(ReceiptStatus status) {
        Transfer t = transfer(status);
        when(ledger.findByTransferIdOrderByIdAsc(t.getId())).thenReturn(List.of());

        TransferResponse r = service.get(t.getId());

        assertThat(r.receiptStatus()).isEqualTo(status);
        assertThat(r.receiptUrl()).isNull();
        verifyNoInteractions(storage);
    }

    @Test
    void getUnknownTransferFailsWithTransferNotFound() {
        UUID id = UUID.randomUUID();
        when(transfers.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo(ErrorCode.TRANSFER_NOT_FOUND));
    }

    // ---------- receiptLink ----------

    @Test
    void receiptLinkReturnsUrlExpiryAndDetailsHash() {
        Transfer t = transfer(ReceiptStatus.READY);
        Instant expires = Instant.now().plusSeconds(900);
        when(storage.presignDownload(t.getReceiptObjectKey(), "receipt-" + t.getId() + ".pdf"))
                .thenReturn(new ReceiptStorage.Link("http://minio/signed", expires));

        ReceiptLinkResponse link = service.receiptLink(t.getId());

        assertThat(link.url()).isEqualTo("http://minio/signed");
        assertThat(link.expiresAt()).isEqualTo(expires);
        assertThat(link.sha256()).isEqualTo(t.getReceiptSha256());
    }

    @ParameterizedTest
    @EnumSource(value = ReceiptStatus.class, names = {"PENDING", "FAILED"})
    void receiptLinkIsRefusedUntilReceiptIsReady(ReceiptStatus status) {
        Transfer t = transfer(status);

        assertThatThrownBy(() -> service.receiptLink(t.getId()))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo(ErrorCode.RECEIPT_NOT_READY));
        verifyNoInteractions(storage);
    }

    // ---------- receiptBytes ----------

    @Test
    void receiptBytesReadsTheStoredObject() {
        Transfer t = transfer(ReceiptStatus.READY);
        byte[] pdf = {'%', 'P', 'D', 'F'};
        when(storage.get(t.getReceiptObjectKey())).thenReturn(pdf);

        assertThat(service.receiptBytes(t.getId())).isEqualTo(pdf);
    }

    @Test
    void receiptBytesIsRefusedWhenReceiptIsNotReady() {
        Transfer t = transfer(ReceiptStatus.PENDING);

        assertThatThrownBy(() -> service.receiptBytes(t.getId()))
                .satisfies(e -> assertThat(codeOf(e).code()).isEqualTo(ErrorCode.RECEIPT_NOT_READY));
        verifyNoInteractions(storage);
    }
}
