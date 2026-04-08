package com.wpanther.pdfsigning.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SignedPdfDocument}.
 * Tests the aggregate root's state machine and business invariants.
 */
@DisplayName("SignedPdfDocument Tests")
class SignedPdfDocumentTest {

    @Nested
    @DisplayName("create() factory method")
    class CreateMethod {

        @Test
        @DisplayName("Should create document in PENDING state")
        void shouldCreatePendingDocument() {
            // When
            SignedPdfDocument document = SignedPdfDocument.create(
                "invoice-123",
                "INV-2024-001",
                "http://example.com/test.pdf",
                1024L,
                "corr-123",
                "TAX_INVOICE"
            );

            // Then
            assertThat(document.getId()).isNotNull();
            assertThat(document.getDocumentId()).isEqualTo("invoice-123");
            assertThat(document.getDocumentNumber()).isEqualTo("INV-2024-001");
            assertThat(document.getDocumentType()).isEqualTo("TAX_INVOICE");
            assertThat(document.getOriginalPdfUrl()).isEqualTo("http://example.com/test.pdf");
            assertThat(document.getOriginalPdfSize()).isEqualTo(1024L);
            assertThat(document.getCorrelationId()).isEqualTo("corr-123");
            assertThat(document.getStatus()).isEqualTo(SigningStatus.PENDING);
            assertThat(document.getRetryCount()).isZero();
            assertThat(document.getCreatedAt()).isNotNull();
            assertThat(document.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should throw exception when invoiceId is blank")
        void shouldThrowOnBlankInvoiceId() {
            assertThatThrownBy(() -> SignedPdfDocument.create(
                "", "INV-001", "http://example.com/test.pdf", 1024L, "corr-123", "TAX_INVOICE"
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId");
        }

        @Test
        @DisplayName("Should throw exception when invoiceNumber is blank")
        void shouldThrowOnBlankInvoiceNumber() {
            assertThatThrownBy(() -> SignedPdfDocument.create(
                "invoice-123", null, "http://example.com/test.pdf", 1024L, "corr-123", "TAX_INVOICE"
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentNumber");
        }

        @Test
        @DisplayName("Should throw exception when originalPdfUrl is blank")
        void shouldThrowOnBlankPdfUrl() {
            assertThatThrownBy(() -> SignedPdfDocument.create(
                "invoice-123", "INV-001", "   ", 1024L, "corr-123", "TAX_INVOICE"
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalPdfUrl");
        }

        @Test
        @DisplayName("Should throw exception when originalPdfSize is null")
        void shouldThrowOnNullPdfSize() {
            assertThatThrownBy(() -> SignedPdfDocument.create(
                "invoice-123", "INV-001", "http://example.com/test.pdf", null, "corr-123", "TAX_INVOICE"
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalPdfSize");
        }
    }

    @Nested
    @DisplayName("startSigning() method")
    class StartSigningMethod {

        @Test
        @DisplayName("Should transition from PENDING to SIGNING")
        void shouldTransitionToSigning() {
            // Given
            SignedPdfDocument document = createTestDocument();

            // When
            document.startSigning();

            // Then
            assertThat(document.getStatus()).isEqualTo(SigningStatus.SIGNING);
            assertThat(document.getUpdatedAt()).isAfterOrEqualTo(document.getCreatedAt());
        }

        @Test
        @DisplayName("Should throw exception when not in PENDING state")
        void shouldThrowWhenNotPending() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.startSigning();

            // When/Then - already in SIGNING
            assertThatThrownBy(() -> document.startSigning())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot start signing")
                .hasMessageContaining("SIGNING");
        }

        @Test
        @DisplayName("Should throw exception when in COMPLETED state")
        void shouldThrowWhenCompleted() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.startSigning();
            completeDocument(document);

            // When/Then
            assertThatThrownBy(() -> document.startSigning())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
        }
    }

    @Nested
    @DisplayName("markCompleted() method")
    class MarkCompletedMethod {

        @Test
        @DisplayName("Should transition from SIGNING to COMPLETED")
        void shouldTransitionToCompleted() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.startSigning();

            // When
            document.markCompleted(
                "/path/signed.pdf",
                "http://example.com/signed.pdf",
                2048L,
                "txn-123",
                "-----BEGIN CERTIFICATE-----",
                "PAdES-BASELINE-T",
                java.time.Instant.now()
            );

            // Then
            assertThat(document.getStatus()).isEqualTo(SigningStatus.COMPLETED);
            assertThat(document.getSignedPdfPath()).isEqualTo("/path/signed.pdf");
            assertThat(document.getSignedPdfUrl()).isEqualTo("http://example.com/signed.pdf");
            assertThat(document.getSignedPdfSize()).isEqualTo(2048L);
            assertThat(document.getTransactionId()).isEqualTo("txn-123");
            assertThat(document.getCertificate()).isNotEmpty();
            assertThat(document.getSignatureLevel()).isEqualTo("PAdES-BASELINE-T");
            assertThat(document.getSignatureTimestamp()).isNotNull();
            assertThat(document.getCompletedAt()).isNotNull();
            assertThat(document.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("Should throw exception when not in SIGNING state")
        void shouldThrowWhenNotSigning() {
            // Given
            SignedPdfDocument document = createTestDocument();

            // When/Then
            assertThatThrownBy(() -> document.markCompleted(
                "/path/signed.pdf", "http://example.com/signed.pdf", 2048L,
                "txn-123", "cert", "PAdES", java.time.Instant.now()
            ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot complete")
                .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("Should throw exception when signedPdfPath is blank")
        void shouldThrowOnBlankPath() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.startSigning();

            // When/Then
            assertThatThrownBy(() -> document.markCompleted(
                "", "http://example.com/signed.pdf", 2048L,
                "txn-123", "cert", "PAdES", java.time.Instant.now()
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signedPdfPath");
        }

        @Test
        @DisplayName("Should throw exception when signedPdfUrl is blank")
        void shouldThrowOnBlankUrl() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.startSigning();

            // When/Then
            assertThatThrownBy(() -> document.markCompleted(
                "/path/signed.pdf", null, 2048L,
                "txn-123", "cert", "PAdES", java.time.Instant.now()
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signedPdfUrl");
        }
    }

    @Nested
    @DisplayName("markFailed() method")
    class MarkFailedMethod {

        @Test
        @DisplayName("Should transition to FAILED state")
        void shouldTransitionToFailed() {
            // Given
            SignedPdfDocument document = createTestDocument();

            // When
            document.markFailed("Signing service unavailable");

            // Then
            assertThat(document.getStatus()).isEqualTo(SigningStatus.FAILED);
            assertThat(document.getErrorMessage()).isEqualTo("Signing service unavailable");
            assertThat(document.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should throw exception when error message is blank")
        void shouldThrowOnBlankErrorMessage() {
            // Given
            SignedPdfDocument document = createTestDocument();

            // When/Then
            assertThatThrownBy(() -> document.markFailed("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorMessage");
        }
    }

    @Nested
    @DisplayName("incrementRetryCount() method")
    class IncrementRetryCountMethod {

        @Test
        @DisplayName("Should increment retry count")
        void shouldIncrementRetryCount() {
            // Given
            SignedPdfDocument document = createTestDocument();

            // When
            int newCount = document.incrementRetryCount();

            // Then
            assertThat(newCount).isEqualTo(1);
            assertThat(document.getRetryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should increment multiple times")
        void shouldIncrementMultipleTimes() {
            // Given
            SignedPdfDocument document = createTestDocument();

            // When
            document.incrementRetryCount();
            document.incrementRetryCount();
            int newCount = document.incrementRetryCount();

            // Then
            assertThat(newCount).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("resetForRetry() method")
    class ResetForRetryMethod {

        @Test
        @DisplayName("Should reset from FAILED to PENDING")
        void shouldResetToPending() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.markFailed("Error");
            document.incrementRetryCount();

            // When
            document.resetForRetry();

            // Then
            assertThat(document.getStatus()).isEqualTo(SigningStatus.PENDING);
            assertThat(document.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("Should throw exception when not in FAILED state")
        void shouldThrowWhenNotFailed() {
            // Given
            SignedPdfDocument document = createTestDocument();

            // When/Then
            assertThatThrownBy(() -> document.resetForRetry())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reset")
                .hasMessageContaining("PENDING");
        }
    }

    @Nested
    @DisplayName("canRetry() method")
    class CanRetryMethod {

        @Test
        @DisplayName("Should return true when FAILED and under max retries")
        void shouldReturnTrueWhenCanRetry() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.markFailed("Error");
            document.incrementRetryCount();

            // When/Then
            assertThat(document.canRetry()).isTrue();
        }

        @Test
        @DisplayName("Should return false when max retries exceeded")
        void shouldReturnFalseWhenMaxExceeded() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.markFailed("Error");
            document.incrementRetryCount();
            document.incrementRetryCount();
            document.incrementRetryCount();

            // When/Then
            assertThat(document.canRetry()).isFalse();
        }

        @Test
        @DisplayName("Should return false when not in FAILED state")
        void shouldReturnFalseWhenNotFailed() {
            // Given
            SignedPdfDocument document = createTestDocument();

            // When/Then
            assertThat(document.canRetry()).isFalse();
        }
    }

    @Nested
    @DisplayName("hasExhaustedRetries() method")
    class HasExhaustedRetriesMethod {

        @Test
        @DisplayName("Should return false when retry count is below max")
        void shouldReturnFalseWhenBelowMax() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.markFailed("Error");
            document.incrementRetryCount(); // retryCount = 1

            // When/Then
            assertThat(document.hasExhaustedRetries()).isFalse();
        }

        @Test
        @DisplayName("Should return true when retry count reaches MAX_RETRY_ATTEMPTS")
        void shouldReturnTrueWhenAtMax() {
            // Given
            SignedPdfDocument document = createTestDocument();
            document.markFailed("Error");
            for (int i = 0; i < SignedPdfDocument.MAX_RETRY_ATTEMPTS; i++) {
                document.incrementRetryCount();
            }

            // When/Then
            assertThat(document.hasExhaustedRetries()).isTrue();
        }

        @Test
        @DisplayName("Should return false for a fresh document with no retries")
        void shouldReturnFalseForFreshDocument() {
            // Given
            SignedPdfDocument document = createTestDocument();

            // When/Then
            assertThat(document.hasExhaustedRetries()).isFalse();
        }
    }

    @Nested
    @DisplayName("State query methods")
    class StateQueryMethods {

        @Test
        @DisplayName("isCompleted should return true only when COMPLETED")
        void testIsCompleted() {
            SignedPdfDocument document = createTestDocument();

            assertThat(document.isCompleted()).isFalse();

            document.startSigning();
            assertThat(document.isCompleted()).isFalse();

            completeDocument(document);
            assertThat(document.isCompleted()).isTrue();
        }

        @Test
        @DisplayName("isFailed should return true only when FAILED")
        void testIsFailed() {
            SignedPdfDocument document = createTestDocument();

            assertThat(document.isFailed()).isFalse();

            document.markFailed("Error");
            assertThat(document.isFailed()).isTrue();
        }

        @Test
        @DisplayName("isSigning should return true only when SIGNING")
        void testIsSigning() {
            SignedPdfDocument document = createTestDocument();

            assertThat(document.isSigning()).isFalse();

            document.startSigning();
            assertThat(document.isSigning()).isTrue();

            completeDocument(document);
            assertThat(document.isSigning()).isFalse();
        }
    }

    // Helper methods

    private SignedPdfDocument createTestDocument() {
        return SignedPdfDocument.create(
            "invoice-123",
            "INV-2024-001",
            "http://example.com/test.pdf",
            1024L,
            "corr-123",
            "TAX_INVOICE"
        );
    }

    private void completeDocument(SignedPdfDocument document) {
        document.markCompleted(
            "/path/signed.pdf",
            "http://example.com/signed.pdf",
            2048L,
            "txn-123",
            "-----BEGIN CERTIFICATE-----",
            "PAdES-BASELINE-T",
            java.time.Instant.now()
        );
    }
}
