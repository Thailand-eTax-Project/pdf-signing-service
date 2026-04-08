package com.wpanther.pdfsigning.domain.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Aggregate Root for signed PDF documents.
 *
 * Manages the lifecycle of PDF document signing with state transitions:
 * PENDING → SIGNING → COMPLETED
 *                  → FAILED
 *
 * Enforces business invariants:
 * - State transitions must follow the state machine
 * - Cannot sign a document that's already signed
 * - Cannot complete without signing first
 * - Tracks retry attempts up to {@link #MAX_RETRY_ATTEMPTS}
 */
@Getter
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PUBLIC) // Public for MapStruct
public class SignedPdfDocument {

    /** Maximum number of signing retry attempts before the document is permanently failed. */
    public static final int MAX_RETRY_ATTEMPTS = 3;

    private final SignedPdfDocumentId id;
    private final String documentId;
    private final String documentNumber;
    private final String documentType;
    private final String originalPdfUrl;
    private final Long originalPdfSize;

    private String signedPdfPath;
    private String signedPdfUrl;
    private Long signedPdfSize;
    private String transactionId;
    private String certificate;
    private String signatureLevel;
    private Instant signatureTimestamp;

    private SigningStatus status;
    private String errorMessage;
    private int retryCount;

    private final LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
    private final String correlationId;

    /**
     * Creates a new SignedPdfDocument in PENDING state.
     *
     * @param documentId document identifier
     * @param documentNumber human-readable document number
     * @param originalPdfUrl URL of the unsigned PDF
     * @param originalPdfSize size of the unsigned PDF in bytes
     * @param correlationId correlation ID for tracing
     * @param documentType document type (INVOICE, TAX_INVOICE, etc.)
     * @return new SignedPdfDocument in PENDING state
     */
    public static SignedPdfDocument create(
            String documentId,
            String documentNumber,
            String originalPdfUrl,
            Long originalPdfSize,
            String correlationId,
            String documentType) {

        validateNotBlank(documentId, "documentId");
        validateNotBlank(documentNumber, "documentNumber");
        validateNotBlank(originalPdfUrl, "originalPdfUrl");
        validateNotNull(originalPdfSize, "originalPdfSize");

        LocalDateTime now = LocalDateTime.now();

        return SignedPdfDocument.builder()
                .id(SignedPdfDocumentId.generate())
                .documentId(documentId)
                .documentNumber(documentNumber)
                .documentType(documentType)
                .originalPdfUrl(originalPdfUrl)
                .originalPdfSize(originalPdfSize)
                .status(SigningStatus.PENDING)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .correlationId(correlationId)
                .build();
    }

    /**
     * Starts the signing process.
     * Transitions from PENDING to SIGNING.
     *
     * @throws IllegalStateException if not in PENDING state
     */
    public void startSigning() {
        if (status != SigningStatus.PENDING) {
            throw new IllegalStateException(
                    String.format("Cannot start signing from status %s. Expected PENDING.", status));
        }
        this.status = SigningStatus.SIGNING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Marks the signing process as completed with signing results.
     * Transitions from SIGNING to COMPLETED.
     *
     * @param signedPdfPath filesystem path to the signed PDF
     * @param signedPdfUrl public URL to access the signed PDF
     * @param signedPdfSize size of the signed PDF in bytes
     * @param transactionId CSC API transaction ID
     * @param certificate PEM-encoded signing certificate
     * @param signatureLevel signature level (e.g., PAdES-BASELINE-T)
     * @param signatureTimestamp timestamp from the signing service
     * @throws IllegalStateException if not in SIGNING state
     */
    public void markCompleted(
            String signedPdfPath,
            String signedPdfUrl,
            Long signedPdfSize,
            String transactionId,
            String certificate,
            String signatureLevel,
            Instant signatureTimestamp) {

        if (status != SigningStatus.SIGNING) {
            throw new IllegalStateException(
                    String.format("Cannot complete from status %s. Expected SIGNING.", status));
        }

        validateNotBlank(signedPdfPath, "signedPdfPath");
        validateNotBlank(signedPdfUrl, "signedPdfUrl");
        validateNotNull(signedPdfSize, "signedPdfSize");
        validateNotBlank(transactionId, "transactionId");
        validateNotBlank(certificate, "certificate");
        validateNotBlank(signatureLevel, "signatureLevel");
        validateNotNull(signatureTimestamp, "signatureTimestamp");

        this.signedPdfPath = signedPdfPath;
        this.signedPdfUrl = signedPdfUrl;
        this.signedPdfSize = signedPdfSize;
        this.transactionId = transactionId;
        this.certificate = certificate;
        this.signatureLevel = signatureLevel;
        this.signatureTimestamp = signatureTimestamp;
        this.status = SigningStatus.COMPLETED;
        this.errorMessage = null;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Marks the signing process as failed with an error message.
     * Can transition from any state to FAILED.
     *
     * @param errorMessage the error message
     */
    public void markFailed(String errorMessage) {
        validateNotBlank(errorMessage, "errorMessage");

        this.status = SigningStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Increments the retry count.
     * Used when retrying a failed signing attempt.
     *
     * @return the new retry count
     */
    public int incrementRetryCount() {
        this.retryCount++;
        this.updatedAt = LocalDateTime.now();
        return this.retryCount;
    }

    /**
     * Resets to PENDING state for retry.
     * Transitions from FAILED to PENDING.
     *
     * @throws IllegalStateException if not in FAILED state
     */
    public void resetForRetry() {
        if (status != SigningStatus.FAILED) {
            throw new IllegalStateException(
                    String.format("Cannot reset from status %s. Expected FAILED.", status));
        }
        this.status = SigningStatus.PENDING;
        this.errorMessage = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Checks if this document can be retried.
     * Returns true only when the document is FAILED and has not yet reached {@link #MAX_RETRY_ATTEMPTS}.
     *
     * @return true if status is FAILED and retry count is below the maximum
     */
    public boolean canRetry() {
        return status == SigningStatus.FAILED && retryCount < MAX_RETRY_ATTEMPTS;
    }

    /**
     * Checks if this document has exhausted all retry attempts.
     * Retry count is incremented by the caller before this check;
     * returns true when the count meets or exceeds {@link #MAX_RETRY_ATTEMPTS}.
     *
     * @return true if retry count has reached the maximum
     */
    public boolean hasExhaustedRetries() {
        return retryCount >= MAX_RETRY_ATTEMPTS;
    }

    /**
     * Checks if signing is completed.
     *
     * @return true if status is COMPLETED
     */
    public boolean isCompleted() {
        return status == SigningStatus.COMPLETED;
    }

    /**
     * Checks if signing has failed.
     *
     * @return true if status is FAILED
     */
    public boolean isFailed() {
        return status == SigningStatus.FAILED;
    }

    /**
     * Checks if signing is in progress.
     *
     * @return true if status is SIGNING
     */
    public boolean isSigning() {
        return status == SigningStatus.SIGNING;
    }

    // Validation helpers

    private static void validateNotBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
    }

    private static void validateNotNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
    }
}
