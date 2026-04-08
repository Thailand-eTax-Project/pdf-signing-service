package com.wpanther.pdfsigning.application.dto.event;

import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ProcessPdfSigningCommand.
 */
@DisplayName("ProcessPdfSigningCommand Tests")
class ProcessPdfSigningCommandTest {

    @Test
    @DisplayName("Should create command with all fields")
    void shouldCreateCommandWithAllFields() {
        // Given
        String sagaId = "saga-123";
        SagaStep sagaStep = SagaStep.SIGN_PDF;
        String correlationId = "corr-456";
        String documentId = "doc-789";
        String documentNumber = "DOC-2024-001";
        String documentType = "INVOICE";
        String pdfUrl = "http://example.com/file.pdf";
        Long pdfSize = 12345L;
        Boolean xmlEmbedded = true;

        // When
        ProcessPdfSigningCommand command = new ProcessPdfSigningCommand(
            sagaId, sagaStep, correlationId,
            documentId, documentNumber, documentType,
            pdfUrl, pdfSize, xmlEmbedded
        );

        // Then
        assertThat(command.getSagaId()).isEqualTo(sagaId);
        assertThat(command.getSagaStep()).isEqualTo(sagaStep);
        assertThat(command.getCorrelationId()).isEqualTo(correlationId);
        assertThat(command.getDocumentId()).isEqualTo(documentId);
        assertThat(command.getDocumentNumber()).isEqualTo(documentNumber);
        assertThat(command.getDocumentType()).isEqualTo(documentType);
        assertThat(command.getPdfUrl()).isEqualTo(pdfUrl);
        assertThat(command.getPdfSize()).isEqualTo(pdfSize);
        assertThat(command.getXmlEmbedded()).isEqualTo(xmlEmbedded);
    }

    @Test
    @DisplayName("Should deserialize from JSON")
    void shouldDeserializeFromJson() {
        // Given - JSON representation (simulating deserialization)
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        String eventType = "ProcessPdfSigning";
        int version = 1;

        // When
        ProcessPdfSigningCommand command = new ProcessPdfSigningCommand(
            eventId, occurredAt, eventType, version,
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "DOC-2024-001", "INVOICE",
            "http://example.com/file.pdf", 12345L, true
        );

        // Then
        assertThat(command.getSagaId()).isEqualTo("saga-123");
        assertThat(command.getSagaStep()).isEqualTo(SagaStep.SIGN_PDF);
        assertThat(command.getDocumentId()).isEqualTo("doc-789");
    }
}
