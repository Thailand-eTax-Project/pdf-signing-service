package com.wpanther.pdfsigning.application.port.out;

import java.time.Instant;

/**
 * Outbound port for publishing PDF signing notification events.
 * Consumed by notification-service (not part of saga coordination).
 */
public interface PdfSignedEventPort {

    void publishPdfSignedNotification(
            String sagaId,
            String documentId,
            String documentNumber,
            String documentType,
            String signedDocumentId,
            String signedPdfUrl,
            Long signedPdfSize,
            String signatureLevel,
            Instant signatureTimestamp,
            String correlationId);

    void publishPdfSigningFailureNotification(
            String sagaId,
            String documentId,
            String documentNumber,
            String documentType,
            String errorMessage,
            String correlationId);
}
