package com.wpanther.pdfsigning.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.pdfsigning.application.dto.event.PdfSignedNotificationEvent;
import com.wpanther.pdfsigning.application.dto.event.PdfSigningFailedNotificationEvent;
import com.wpanther.pdfsigning.application.port.out.PdfSignedEventPort;
import com.wpanther.pdfsigning.infrastructure.config.properties.KafkaProperties;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes notification events for the notification-service observer.
 * Notification-service is NOT part of saga - it's a reactive observer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPdfSignedEventAdapter implements PdfSignedEventPort {

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final KafkaProperties kafkaProperties;

    /**
     * Publish notification event when PDF is signed successfully.
     * This is separate from saga reply - orchestrator doesn't consume this.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishPdfSignedNotification(
            String sagaId,
            String documentId,
            String documentNumber,
            String documentType,
            String signedDocumentId,
            String signedPdfUrl,
            Long signedPdfSize,
            String signatureLevel,
            Instant signatureTimestamp,
            String correlationId) {

        PdfSignedNotificationEvent notification = PdfSignedNotificationEvent.create(
            sagaId, documentId, documentNumber, documentType,
            signedDocumentId, signedPdfUrl, signedPdfSize,
            signatureLevel, signatureTimestamp, correlationId
        );

        Map<String, String> headers = new HashMap<>();
        headers.put("eventType", "PdfSigned");
        headers.put("documentType", documentType);
        headers.put("correlationId", correlationId);
        headers.put("documentId", documentId);

        // Use documentId as partition key for all events of the same document
        outboxService.saveWithRouting(
            notification,
            "SignedPdfDocument",
            signedDocumentId,
            kafkaProperties.getTopics().getNotificationEvents(),
            documentId,  // Partition by documentId for ordering
            toJson(headers)
        );

        log.info("Published PdfSigned notification for documentId={}, documentNumber={}, documentType={}",
            documentId, documentNumber, documentType);
    }

    /**
     * Publish notification event when PDF signing fails.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishPdfSigningFailureNotification(
            String sagaId,
            String documentId,
            String documentNumber,
            String documentType,
            String errorMessage,
            String correlationId) {

        PdfSigningFailedNotificationEvent notification = PdfSigningFailedNotificationEvent.create(
            sagaId, documentId, documentNumber, documentType,
            errorMessage, correlationId
        );

        Map<String, String> headers = new HashMap<>();
        headers.put("eventType", "PdfSigningFailed");
        headers.put("documentType", documentType);
        headers.put("correlationId", correlationId);
        headers.put("documentId", documentId);

        try {
            outboxService.saveWithRouting(
                notification,
                "SignedPdfDocument",
                documentId,
                kafkaProperties.getTopics().getNotificationEvents(),
                documentId,
                toJson(headers)
            );

            log.warn("Published PdfSigningFailed notification for documentId={}, error={}",
                documentId, errorMessage);
        } catch (Exception e) {
            log.error("Failed to publish failure notification for documentId={}", documentId, e);
        }
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize headers to JSON", e);
            return "{}";
        }
    }
}
