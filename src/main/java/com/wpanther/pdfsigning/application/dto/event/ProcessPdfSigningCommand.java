package com.wpanther.pdfsigning.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.SagaCommand;
import com.wpanther.saga.domain.enums.SagaStep;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Saga command from orchestrator to sign a PDF document.
 * Consumed from: saga.command.pdf-signing
 *
 * Contains all information needed to download and sign the PDF.
 * Extends SagaCommand which provides sagaId, sagaStep, and correlationId.
 */
@Getter
public class ProcessPdfSigningCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("documentNumber")
    private final String documentNumber;

    @JsonProperty("documentType")
    private final String documentType;

    @JsonProperty("pdfUrl")
    private final String pdfUrl;

    @JsonProperty("pdfSize")
    private final Long pdfSize;

    @JsonProperty("xmlEmbedded")
    private final Boolean xmlEmbedded;

    /**
     * Constructor for deserialization from Kafka.
     * Used by Apache Camel JSON unmarshalling.
     */
    @JsonCreator
    public ProcessPdfSigningCommand(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("version") int version,
        @JsonProperty("sagaId") String sagaId,
        @JsonProperty("sagaStep") SagaStep sagaStep,
        @JsonProperty("correlationId") String correlationId,
        @JsonProperty("documentId") String documentId,
        @JsonProperty("documentNumber") String documentNumber,
        @JsonProperty("documentType") String documentType,
        @JsonProperty("pdfUrl") String pdfUrl,
        @JsonProperty("pdfSize") Long pdfSize,
        @JsonProperty("xmlEmbedded") Boolean xmlEmbedded
    ) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.documentType = documentType;
        this.pdfUrl = pdfUrl;
        this.pdfSize = pdfSize;
        this.xmlEmbedded = xmlEmbedded;
    }

    /**
     * Convenience constructor for testing.
     */
    public ProcessPdfSigningCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                    String documentId, String documentNumber, String documentType,
                                    String pdfUrl, Long pdfSize, Boolean xmlEmbedded) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.documentType = documentType;
        this.pdfUrl = pdfUrl;
        this.pdfSize = pdfSize;
        this.xmlEmbedded = xmlEmbedded;
    }
}
