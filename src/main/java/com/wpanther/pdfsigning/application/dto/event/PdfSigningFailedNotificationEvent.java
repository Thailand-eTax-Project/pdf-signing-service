package com.wpanther.pdfsigning.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.TraceEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Notification event when PDF signing fails.
 * Published to: pdf.signed (via outbox pattern)
 *
 * This is a notification event for the notification-service observer.
 * <p>
 * Extends TraceEvent as it represents an observational event for audit/notification purposes.
 */
@Getter
public class PdfSigningFailedNotificationEvent extends TraceEvent {

    private static final String TRACE_TYPE = "PdfSigningFailed";
    private static final String SOURCE = "pdf-signing-service";

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("documentNumber")
    private final String documentNumber;

    @JsonProperty("documentType")
    private final String documentType;

    @JsonProperty("errorMessage")
    private final String errorMessage;

    /**
     * Factory method for creating new notification events.
     */
    public static PdfSigningFailedNotificationEvent create(
            String sagaId,
            String documentId,
            String documentNumber,
            String documentType,
            String errorMessage,
            String correlationId) {

        return new PdfSigningFailedNotificationEvent(
            sagaId, documentId, documentNumber, documentType,
            errorMessage, correlationId
        );
    }

    /**
     * Constructor for creating new events.
     */
    private PdfSigningFailedNotificationEvent(
            String sagaId,
            String documentId,
            String documentNumber,
            String documentType,
            String errorMessage,
            String correlationId) {

        super(sagaId, correlationId, SOURCE, TRACE_TYPE, null);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.documentType = documentType;
        this.errorMessage = errorMessage;
    }

    /**
     * Constructor for deserialization from Kafka.
     */
    @JsonCreator
    public PdfSigningFailedNotificationEvent(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("version") int version,
        @JsonProperty("sagaId") String sagaId,
        @JsonProperty("correlationId") String correlationId,
        @JsonProperty("source") String source,
        @JsonProperty("traceType") String traceType,
        @JsonProperty("context") String context,
        @JsonProperty("documentId") String documentId,
        @JsonProperty("documentNumber") String documentNumber,
        @JsonProperty("documentType") String documentType,
        @JsonProperty("errorMessage") String errorMessage
    ) {
        super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.documentType = documentType;
        this.errorMessage = errorMessage;
    }
}
