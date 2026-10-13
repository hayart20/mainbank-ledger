package com.mainbank.receipt.api;

import com.mainbank.receipt.dto.ReceiptLinkResponse;
import com.mainbank.receipt.exception.GlobalExceptionHandler;
import com.mainbank.receipt.exception.ReceiptException;
import com.mainbank.receipt.service.ReceiptQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReceiptControllerTest {

    @Mock ReceiptQueryService queries;

    private MockMvc mvc;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ReceiptController(queries))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void defaultModeReturnsThePresignedLink() throws Exception {
        when(queries.link(id)).thenReturn(new ReceiptLinkResponse("http://minio/x?sig", Instant.parse("2026-01-01T00:00:00Z"), "abc"));

        mvc.perform(get("/api/v1/receipts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://minio/x?sig"))
                .andExpect(jsonPath("$.sha256").value("abc"));
    }

    @Test
    void streamModePipesThePdf() throws Exception {
        when(queries.bytes(id)).thenReturn(new byte[]{'%', 'P', 'D', 'F'});

        mvc.perform(get("/api/v1/receipts/{id}?mode=stream", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"receipt-" + id + ".pdf\""))
                .andExpect(content().bytes(new byte[]{'%', 'P', 'D', 'F'}));
    }

    @Test
    void unknownReceiptIs404() throws Exception {
        when(queries.link(id)).thenThrow(new ReceiptException(ReceiptException.Code.RECEIPT_NOT_FOUND, "none"));

        mvc.perform(get("/api/v1/receipts/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECEIPT_NOT_FOUND"));
    }

    @Test
    void receiptThatIsNotReadyIs409() throws Exception {
        when(queries.link(id)).thenThrow(new ReceiptException(ReceiptException.Code.RECEIPT_NOT_READY, "pending"));

        mvc.perform(get("/api/v1/receipts/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_NOT_READY"));
    }
}
