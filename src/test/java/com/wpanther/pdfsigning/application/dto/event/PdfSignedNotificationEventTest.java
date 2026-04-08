package com.wpanther.pdfsigning.application.dto.event;

import com.wpanther.saga.domain.model.TraceEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PdfSignedNotificationEvent.
 */
@DisplayName("PdfSignedNotificationEvent Tests")
class PdfSignedNotificationEventTest {

    @Nested
    @DisplayName("PdfSignedNotificationEvent")
    class SuccessEventTests {

        @Test
        @DisplayName("Should create notification event with all fields")
        void shouldCreateNotificationEvent() {
            // Given
            String sagaId = "saga-123";
            String documentId = "doc-123";
            String documentNumber = "DOC-2024-001";
            String documentType = "INVOICE";
            String signedDocumentId = "signed-doc-789";
            String signedPdfUrl = "http://example.com/signed.pdf";
            Long signedPdfSize = 54321L;
            String signatureLevel = "PAdES-BASELINE-T";
            Instant signatureTimestamp = Instant.now();
            String correlationId = "corr-456";

            // When
            PdfSignedNotificationEvent event = PdfSignedNotificationEvent.create(
                sagaId, documentId, documentNumber, documentType,
                signedDocumentId, signedPdfUrl, signedPdfSize,
                signatureLevel, signatureTimestamp, correlationId
            );

            // Then
            assertThat(event).isInstanceOf(TraceEvent.class);
            assertThat(event.getTraceType()).isEqualTo("PdfSigned");
            assertThat(event.getSagaId()).isEqualTo(sagaId);
            assertThat(event.getSource()).isEqualTo("pdf-signing-service");
            assertThat(event.getDocumentId()).isEqualTo(documentId);
            assertThat(event.getDocumentNumber()).isEqualTo(documentNumber);
            assertThat(event.getDocumentType()).isEqualTo(documentType);
            assertThat(event.getSignedDocumentId()).isEqualTo(signedDocumentId);
            assertThat(event.getSignedPdfUrl()).isEqualTo(signedPdfUrl);
            assertThat(event.getSignedPdfSize()).isEqualTo(signedPdfSize);
            assertThat(event.getSignatureLevel()).isEqualTo(signatureLevel);
            assertThat(event.getSignatureTimestamp()).isEqualTo(signatureTimestamp);
            assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        }

        @Test
        @DisplayName("Should create event via JsonCreator constructor for deserialization")
        void shouldCreateEventViaJsonCreator() {
            // Given - parameters as they would come from deserialized JSON
            UUID eventId = UUID.randomUUID();
            Instant occurredAt = Instant.now();
            String eventType = "PdfSigned";
            int version = 1;
            String sagaId = "saga-456";
            String source = "pdf-signing-service";
            String traceType = "PdfSigned";
            String context = null;
            String documentId = "doc-789";
            String documentNumber = "DOC-2024-002";
            String documentType = "TAX_INVOICE";
            String signedDocumentId = "doc-123";
            String signedPdfUrl = "http://example.com/signed2.pdf";
            Long signedPdfSize = 1024L;
            String signatureLevel = "PAdES-BASELINE-B";
            Instant signatureTimestamp = Instant.now().minusSeconds(60);
            String correlationId = "corr-789";

            // When - using @JsonCreator constructor
            PdfSignedNotificationEvent event = new PdfSignedNotificationEvent(
                eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context,
                documentId, documentNumber, documentType, signedDocumentId, signedPdfUrl, signedPdfSize,
                signatureLevel, signatureTimestamp
            );

            // Then
            assertThat(event.getEventId()).isEqualTo(eventId);
            assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
            assertThat(event.getDocumentId()).isEqualTo(documentId);
            assertThat(event.getSignedPdfUrl()).isEqualTo(signedPdfUrl);
            assertThat(event.getSignatureTimestamp()).isEqualTo(signatureTimestamp);
            assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        }

        @Test
        @DisplayName("Should implement equals and hashCode correctly")
        void shouldImplementEqualsAndHashCode() {
            // Given - Create events using JsonCreator with same eventId for equality test
            UUID eventId = UUID.randomUUID();
            Instant occurredAt = Instant.now();

            PdfSignedNotificationEvent event1 = new PdfSignedNotificationEvent(
                eventId, occurredAt, "PdfSigned", 1, "saga-1", "corr-1", "pdf-signing-service",
                "PdfSigned", null, "doc-1", "DOC-001", "INVOICE",
                "doc-1", "http://example.com/1.pdf", 1000L,
                "PAdES-BASELINE-T", Instant.now()
            );

            PdfSignedNotificationEvent event2 = new PdfSignedNotificationEvent(
                eventId, occurredAt, "PdfSigned", 1, "saga-1", "corr-1", "pdf-signing-service",
                "PdfSigned", null, "doc-1", "DOC-001", "INVOICE",
                "doc-1", "http://example.com/1.pdf", 1000L,
                "PAdES-BASELINE-T", Instant.now()
            );

            PdfSignedNotificationEvent event3 = new PdfSignedNotificationEvent(
                UUID.randomUUID(), occurredAt, "PdfSigned", 1, "saga-2", "corr-2", "pdf-signing-service",
                "PdfSigned", null, "doc-2", "DOC-002", "INVOICE",
                "doc-2", "http://example.com/2.pdf", 2000L,
                "PAdES-BASELINE-B", Instant.now()
            );

            // Then - equals
            assertThat(event1).isEqualTo(event2);
            assertThat(event1).isNotEqualTo(event3);
            assertThat(event1).isNotEqualTo(null);
            assertThat(event1).isNotEqualTo("not an event");

            // Then - hashCode
            assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
            assertThat(event1.hashCode()).isNotEqualTo(event3.hashCode());
        }

        @Test
        @DisplayName("Should implement toString")
        void shouldImplementToString() {
            // Given
            PdfSignedNotificationEvent event = PdfSignedNotificationEvent.create(
                "saga-1", "doc-1", "DOC-001", "INVOICE",
                "doc-1", "http://example.com/1.pdf", 1000L,
                "PAdES-BASELINE-T", Instant.now(), "corr-1"
            );

            // When
            String toString = event.toString();

            // Then
            assertThat(toString).isNotEmpty();
            assertThat(toString).contains("PdfSigned");
            assertThat(toString).contains("eventId");
            assertThat(toString).contains("sagaId");
        }
    }

    @Nested
    @DisplayName("PdfSigningFailedNotificationEvent")
    class FailedEventTests {

        @Test
        @DisplayName("Should create failure notification event")
        void shouldCreateFailureNotification() {
            // Given
            String sagaId = "saga-123";
            String documentId = "doc-123";
            String documentNumber = "DOC-2024-001";
            String documentType = "INVOICE";
            String errorMessage = "Signing failed: CSC API timeout";
            String correlationId = "corr-456";

            // When
            PdfSigningFailedNotificationEvent event = PdfSigningFailedNotificationEvent.create(
                sagaId, documentId, documentNumber, documentType,
                errorMessage, correlationId
            );

            // Then
            assertThat(event).isInstanceOf(TraceEvent.class);
            assertThat(event.getTraceType()).isEqualTo("PdfSigningFailed");
            assertThat(event.getSagaId()).isEqualTo(sagaId);
            assertThat(event.getSource()).isEqualTo("pdf-signing-service");
            assertThat(event.getDocumentId()).isEqualTo(documentId);
            assertThat(event.getDocumentNumber()).isEqualTo(documentNumber);
            assertThat(event.getDocumentType()).isEqualTo(documentType);
            assertThat(event.getErrorMessage()).isEqualTo(errorMessage);
            assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        }

        @Test
        @DisplayName("Should create failed event via JsonCreator constructor for deserialization")
        void shouldCreateFailedEventViaJsonCreator() {
            // Given - parameters as they would come from deserialized JSON
            UUID eventId = UUID.randomUUID();
            Instant occurredAt = Instant.now();
            String eventType = "PdfSigningFailed";
            int version = 1;
            String sagaId = "saga-456";
            String source = "pdf-signing-service";
            String traceType = "PdfSigningFailed";
            String context = null;
            String documentId = "doc-789";
            String documentNumber = "DOC-2024-002";
            String documentType = "TAX_INVOICE";
            String errorMessage = "Network error";
            String correlationId = "corr-789";

            // When - using @JsonCreator constructor
            PdfSigningFailedNotificationEvent event = new PdfSigningFailedNotificationEvent(
                eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context,
                documentId, documentNumber, documentType, errorMessage
            );

            // Then
            assertThat(event.getEventId()).isEqualTo(eventId);
            assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
            assertThat(event.getDocumentId()).isEqualTo(documentId);
            assertThat(event.getErrorMessage()).isEqualTo(errorMessage);
            assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        }

        @Test
        @DisplayName("Should implement equals and hashCode correctly")
        void shouldImplementEqualsAndHashCode() {
            // Given - Create events using JsonCreator with same eventId for equality test
            UUID eventId = UUID.randomUUID();
            Instant occurredAt = Instant.now();

            PdfSigningFailedNotificationEvent event1 = new PdfSigningFailedNotificationEvent(
                eventId, occurredAt, "PdfSigningFailed", 1, "saga-1", "corr-1", "pdf-signing-service",
                "PdfSigningFailed", null, "doc-1", "DOC-001", "INVOICE",
                "Error 1"
            );

            PdfSigningFailedNotificationEvent event2 = new PdfSigningFailedNotificationEvent(
                eventId, occurredAt, "PdfSigningFailed", 1, "saga-1", "corr-1", "pdf-signing-service",
                "PdfSigningFailed", null, "doc-1", "DOC-001", "INVOICE",
                "Error 1"
            );

            PdfSigningFailedNotificationEvent event3 = new PdfSigningFailedNotificationEvent(
                UUID.randomUUID(), occurredAt, "PdfSigningFailed", 1, "saga-2", "corr-2", "pdf-signing-service",
                "PdfSigningFailed", null, "doc-2", "DOC-002", "INVOICE",
                "Error 2"
            );

            // Then - equals
            assertThat(event1).isEqualTo(event2);
            assertThat(event1).isNotEqualTo(event3);
            assertThat(event1).isNotEqualTo(null);
            assertThat(event1).isNotEqualTo("not an event");

            // Then - hashCode
            assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
            assertThat(event1.hashCode()).isNotEqualTo(event3.hashCode());
        }

        @Test
        @DisplayName("Should implement toString")
        void shouldImplementToString() {
            // Given
            PdfSigningFailedNotificationEvent event = PdfSigningFailedNotificationEvent.create(
                "saga-1", "doc-1", "DOC-001", "INVOICE",
                "Error message", "corr-1"
            );

            // When
            String toString = event.toString();

            // Then
            assertThat(toString).isNotEmpty();
            assertThat(toString).contains("PdfSigningFailed");
            assertThat(toString).contains("eventId");
            assertThat(toString).contains("sagaId");
        }
    }
}
