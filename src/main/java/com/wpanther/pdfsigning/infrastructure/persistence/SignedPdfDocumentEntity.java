package com.wpanther.pdfsigning.infrastructure.persistence;

import com.wpanther.pdfsigning.domain.model.SigningStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity for signed_pdf_documents table.
 *
 * Maps to the database schema created by V1__create_signed_pdf_documents_table.sql
 */
@Entity
@Table(name = "signed_pdf_documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignedPdfDocumentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "document_id", nullable = false, unique = true, length = 100)
    private String documentId;

    @Column(name = "document_number", nullable = false, length = 50)
    private String documentNumber;

    @Column(name = "document_type", length = 50)
    private String documentType;

    @Column(name = "original_pdf_url", nullable = false, length = 500)
    private String originalPdfUrl;

    @Column(name = "original_pdf_size", nullable = false)
    private Long originalPdfSize;

    @Column(name = "signed_pdf_path", length = 500)
    private String signedPdfPath;

    @Column(name = "signed_pdf_url", length = 500)
    private String signedPdfUrl;

    @Column(name = "signed_pdf_size")
    private Long signedPdfSize;

    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(name = "certificate", columnDefinition = "TEXT")
    private String certificate;

    @Column(name = "signature_level", length = 50)
    private String signatureLevel;

    @Column(name = "signature_timestamp", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime signatureTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SigningStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (retryCount == null) {
            retryCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
