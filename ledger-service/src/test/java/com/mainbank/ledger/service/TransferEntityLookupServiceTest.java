package com.mainbank.ledger.service;

import com.mainbank.ledger.domain.constant.Currency;
import com.mainbank.ledger.domain.constant.ErrorCode;
import com.mainbank.ledger.domain.constant.TransferType;
import com.mainbank.ledger.domain.entity.TransferEntity;
import com.mainbank.ledger.domain.exception.DomainException;
import com.mainbank.ledger.dto.TransferResponse;
import com.mainbank.ledger.repository.LedgerEntryRepository;
import com.mainbank.ledger.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferEntityLookupServiceTest
{

    @Mock TransferRepository transfers;
    @Mock LedgerEntryRepository ledger;

    private TransferLookupService service;

    @BeforeEach
    void setUp() {
        service = new TransferLookupService(transfers, ledger);
    }

    @Test
    void getReturnsTheTransferWithItsLedgerEntries() {
        TransferEntity t = TransferEntity.completed(UUID.randomUUID(), TransferType.TRANSFER, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), Currency.AMD);
        when(transfers.findById(t.getId())).thenReturn(Optional.of(t));
        when(ledger.findByTransferIdOrderByIdAsc(t.getId())).thenReturn(List.of());

        TransferResponse r = service.get(t.getId());

        assertThat(r.id()).isEqualTo(t.getId());
        assertThat(r.amount()).isEqualByComparingTo("10.00");
        assertThat(r.currency()).isEqualTo(Currency.AMD);
        assertThat(r.ledgerEntries()).isEmpty();
    }

    @Test
    void getUnknownTransferFailsWithTransferNotFound() {
        UUID id = UUID.randomUUID();
        when(transfers.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.TRANSFER_NOT_FOUND));
    }
}
