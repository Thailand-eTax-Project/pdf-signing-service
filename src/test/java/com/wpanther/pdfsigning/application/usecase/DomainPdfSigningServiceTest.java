package com.wpanther.pdfsigning.application.usecase;

import com.wpanther.pdfsigning.application.usecase.DomainPdfSigningService;
import com.wpanther.pdfsigning.domain.model.*;
import com.wpanther.pdfsigning.domain.repository.SignedPdfDocumentRepository;
import com.wpanther.pdfsigning.application.port.out.DocumentDownloadPort;
import com.wpanther.pdfsigning.application.port.out.DocumentStoragePort;
import com.wpanther.pdfsigning.application.port.out.PdfGenerationPort;
import com.wpanther.pdfsigning.application.port.out.SigningPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DomainPdfSigningService}.
 * <p>
 * These tests demonstrate the improved testability of hexagonal architecture:
 * <ul>
 *   <li>No Spring context needed - pure unit tests</li>
 *   <li>Fast execution - no infrastructure dependencies</li>
 *   <li>Easy mocking - all dependencies are port interfaces</li>
 *   <li>Clear testing of domain logic in isolation</li>
 * </ul>
 * </p>
 */
@DisplayName("DomainPdfSigningService Tests")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DomainPdfSigningServiceTest {

    @Mock
    private SigningPort mockSigningPort;

    @Mock
    private PdfGenerationPort mockPdfPort;

    @Mock
    private DocumentStoragePort mockStoragePort;

    @Mock
    private DocumentDownloadPort mockDownloadPort;

    @Mock
    private SignedPdfDocumentRepository mockRepository;

    @Mock
    private X509Certificate mockCert;

    private DomainPdfSigningService service;

    @BeforeEach
    void setUp() throws java.security.cert.CertificateEncodingException {
        service = new DomainPdfSigningService(
            mockSigningPort,
            mockPdfPort,
            mockStoragePort,
            mockDownloadPort,
            mockRepository
        );
        // Provide a minimal DER-encoded certificate for tests that complete successfully
        when(mockCert.getEncoded()).thenReturn(new byte[]{48, 3, 1, 2, 3});
    }

    /** Convenience: one-element chain containing the shared mock certificate. */
    private X509Certificate[] oneCert() {
        return new X509Certificate[]{mockCert};
    }

    @Nested
    @DisplayName("signPdf() method")
    class SignPdfMethod {

        @Test
        @DisplayName("Should successfully sign PDF with valid inputs")
        void shouldSignPdfSuccessfully() {
            // Given
            String originalPdfUrl = "https://storage.example.com/original.pdf";
            String documentId = "doc-123";
            String documentNumber = "INV-2024-001";
            PadesLevel padesLevel = PadesLevel.BASELINE_B;

            byte[] pdfBytes = "test pdf content".getBytes();
            byte[] digest = new byte[32]; // SHA-256 digest
            byte[] signedPdfBytes = "signed pdf content".getBytes();
            String storageUrl = "https://storage.example.com/documents/signed.pdf";

            when(mockDownloadPort.downloadPdf(originalPdfUrl)).thenReturn(pdfBytes);
            when(mockPdfPort.computeByteRangeDigest(pdfBytes)).thenReturn(digest);
            when(mockSigningPort.signPdfWithCertChain(pdfBytes, digest, padesLevel))
                .thenReturn(new SigningPort.SigningResult(signedPdfBytes, oneCert(), null, null));
            when(mockStoragePort.store(eq(signedPdfBytes), eq(DocumentType.SIGNED_PDF), eq(documentId), eq(documentNumber)))
                .thenReturn(storageUrl);

            // When
            DomainPdfSigningService.SignedPdfResult result = service.signPdf(
                originalPdfUrl, documentId, documentNumber, padesLevel
            );

            // Then
            assertThat(result).isNotNull();
            assertThat(result.signedPdfUrl()).isEqualTo(storageUrl);
            assertThat(result.signedPdfSize()).isEqualTo((long) signedPdfBytes.length);
            assertThat(result.signatureLevel()).isEqualTo("PAdES-BASELINE-B");
            assertThat(result.certificate()).contains("-----BEGIN CERTIFICATE-----");

            // Verify the complete workflow was executed
            verify(mockDownloadPort).downloadPdf(originalPdfUrl);
            verify(mockPdfPort).computeByteRangeDigest(pdfBytes);
            verify(mockSigningPort).signPdfWithCertChain(pdfBytes, digest, padesLevel);
            verify(mockStoragePort).store(eq(signedPdfBytes), eq(DocumentType.SIGNED_PDF), eq(documentId), eq(documentNumber));
        }

        @Test
        @DisplayName("Should fail when download throws exception")
        void shouldFailOnDownloadException() {
            // Given
            String originalPdfUrl = "https://storage.example.com/original.pdf";
            String documentId = "doc-123";
            String documentNumber = "INV-2024-001";
            PadesLevel padesLevel = PadesLevel.BASELINE_B;

            doThrow(new SigningException("Network error downloading PDF"))
                .when(mockDownloadPort).downloadPdf(originalPdfUrl);

            // When & Then
            assertThatThrownBy(() -> service.signPdf(originalPdfUrl, documentId, documentNumber, padesLevel))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Network error downloading PDF");

            // Verify download was attempted but no further processing
            verify(mockDownloadPort).downloadPdf(originalPdfUrl);
            verify(mockPdfPort, never()).computeByteRangeDigest(any());
        }

        @Test
        @DisplayName("Should fail when digest computation throws exception")
        void shouldFailOnDigestException() {
            // Given
            String originalPdfUrl = "https://storage.example.com/original.pdf";
            String documentId = "doc-123";
            String documentNumber = "INV-2024-001";
            PadesLevel padesLevel = PadesLevel.BASELINE_B;

            byte[] pdfBytes = "test pdf content".getBytes();

            when(mockDownloadPort.downloadPdf(originalPdfUrl)).thenReturn(pdfBytes);
            doThrow(new SigningException("Digest computation failed"))
                .when(mockPdfPort).computeByteRangeDigest(pdfBytes);

            // When & Then
            assertThatThrownBy(() -> service.signPdf(originalPdfUrl, documentId, documentNumber, padesLevel))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Digest computation failed");

            // Verify download and digest were attempted
            verify(mockDownloadPort).downloadPdf(originalPdfUrl);
            verify(mockPdfPort).computeByteRangeDigest(pdfBytes);
        }

        @Test
        @DisplayName("Should fail when signing throws exception")
        void shouldFailOnSigningException() {
            // Given
            String originalPdfUrl = "https://storage.example.com/original.pdf";
            String documentId = "doc-123";
            String documentNumber = "INV-2024-001";
            PadesLevel padesLevel = PadesLevel.BASELINE_T;

            byte[] pdfBytes = "test pdf content".getBytes();
            byte[] digest = new byte[32];

            when(mockDownloadPort.downloadPdf(originalPdfUrl)).thenReturn(pdfBytes);
            when(mockPdfPort.computeByteRangeDigest(pdfBytes)).thenReturn(digest);

            doThrow(new SigningException("CSC API error"))
                .when(mockSigningPort).signPdfWithCertChain(pdfBytes, digest, padesLevel);

            // When & Then
            assertThatThrownBy(() -> service.signPdf(originalPdfUrl, documentId, documentNumber, padesLevel))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("CSC API error");

            // Verify workflow progressed to signing stage
            verify(mockDownloadPort).downloadPdf(originalPdfUrl);
            verify(mockPdfPort).computeByteRangeDigest(pdfBytes);
        }

        @Test
        @DisplayName("Should fail when storage throws exception")
        void shouldFailOnStorageException() {
            // Given
            String originalPdfUrl = "https://storage.example.com/original.pdf";
            String documentId = "doc-123";
            String documentNumber = "INV-2024-001";
            PadesLevel padesLevel = PadesLevel.BASELINE_B;

            byte[] pdfBytes = "test pdf content".getBytes();
            byte[] digest = new byte[32];
            byte[] signedPdfBytes = "signed pdf content".getBytes();
            X509Certificate[] certChain = new X509Certificate[0];

            when(mockDownloadPort.downloadPdf(originalPdfUrl)).thenReturn(pdfBytes);
            when(mockPdfPort.computeByteRangeDigest(pdfBytes)).thenReturn(digest);
            when(mockSigningPort.signPdfWithCertChain(pdfBytes, digest, padesLevel))
                .thenReturn(new SigningPort.SigningResult(signedPdfBytes, certChain, null, null));

            doThrow(new StorageException("S3 bucket unavailable"))
                .when(mockStoragePort).store(eq(signedPdfBytes), eq(DocumentType.SIGNED_PDF), eq(documentId), eq(documentNumber));

            // When & Then
            assertThatThrownBy(() -> service.signPdf(originalPdfUrl, documentId, documentNumber, padesLevel))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("S3 bucket unavailable");

            // Verify workflow progressed to storage stage
            verify(mockDownloadPort).downloadPdf(originalPdfUrl);
            verify(mockPdfPort).computeByteRangeDigest(pdfBytes);
            verify(mockSigningPort).signPdfWithCertChain(pdfBytes, digest, padesLevel);
        }

        @Test
        @DisplayName("Should test with BASELINE_T PadesLevel")
        void shouldTestBaselineTLevel() {
            // Given
            String originalPdfUrl = "https://storage.example.com/original.pdf";
            String documentId = "doc-123";
            String documentNumber = "INV-2024-001";
            PadesLevel padesLevel = PadesLevel.BASELINE_T;

            byte[] pdfBytes = "test pdf content".getBytes();
            byte[] digest = new byte[32];
            byte[] signedPdfBytes = "signed pdf content".getBytes();
            String storageUrl = "https://storage.example.com/signed.pdf";

            when(mockDownloadPort.downloadPdf(originalPdfUrl)).thenReturn(pdfBytes);
            when(mockPdfPort.computeByteRangeDigest(pdfBytes)).thenReturn(digest);
            when(mockSigningPort.signPdfWithCertChain(pdfBytes, digest, padesLevel))
                .thenReturn(new SigningPort.SigningResult(signedPdfBytes, oneCert(), null, null));
            when(mockStoragePort.store(eq(signedPdfBytes), eq(DocumentType.SIGNED_PDF), eq(documentId), eq(documentNumber)))
                .thenReturn(storageUrl);

            // When
            DomainPdfSigningService.SignedPdfResult result = service.signPdf(
                originalPdfUrl, documentId, documentNumber, padesLevel
            );

            // Then
            assertThat(result).isNotNull();
            assertThat(result.signatureLevel()).isEqualTo("PAdES-BASELINE-T");
        }

        @Test
        @DisplayName("Should extract filename from storage URL")
        void shouldExtractFilenameFromUrl() {
            // Given
            String originalPdfUrl = "https://storage.example.com/original.pdf";
            String documentId = "doc-123";
            String documentNumber = "INV-2024-001";
            PadesLevel padesLevel = PadesLevel.BASELINE_B;

            byte[] pdfBytes = "test pdf content".getBytes();
            byte[] digest = new byte[32];
            byte[] signedPdfBytes = "signed pdf content".getBytes();
            String storageUrl = "https://storage.example.com/documents/2024/02/27/signed-pdf-abc123.pdf";

            when(mockDownloadPort.downloadPdf(originalPdfUrl)).thenReturn(pdfBytes);
            when(mockPdfPort.computeByteRangeDigest(pdfBytes)).thenReturn(digest);
            when(mockSigningPort.signPdfWithCertChain(pdfBytes, digest, padesLevel))
                .thenReturn(new SigningPort.SigningResult(signedPdfBytes, oneCert(), null, null));
            when(mockStoragePort.store(eq(signedPdfBytes), eq(DocumentType.SIGNED_PDF), eq(documentId), eq(documentNumber)))
                .thenReturn(storageUrl);

            // When
            DomainPdfSigningService.SignedPdfResult result = service.signPdf(
                originalPdfUrl, documentId, documentNumber, padesLevel
            );

            // Then - should extract just the filename
            assertThat(result.signedPdfPath()).isEqualTo("signed-pdf-abc123.pdf");
        }

        @Test
        @DisplayName("Should throw SigningException when CSC returns empty certificate chain")
        void shouldThrowWhenCertChainIsEmpty() {
            // Given
            String originalPdfUrl = "https://storage.example.com/original.pdf";
            String documentId = "doc-123";
            String documentNumber = "INV-2024-001";
            PadesLevel padesLevel = PadesLevel.BASELINE_B;

            byte[] pdfBytes = "test pdf content".getBytes();
            byte[] digest = new byte[32];
            byte[] signedPdfBytes = "signed pdf content".getBytes();
            String storageUrl = "https://storage.example.com/signed.pdf";

            when(mockDownloadPort.downloadPdf(originalPdfUrl)).thenReturn(pdfBytes);
            when(mockPdfPort.computeByteRangeDigest(pdfBytes)).thenReturn(digest);
            when(mockSigningPort.signPdfWithCertChain(pdfBytes, digest, padesLevel))
                .thenReturn(new SigningPort.SigningResult(signedPdfBytes, new X509Certificate[0], null, null));
            when(mockStoragePort.store(eq(signedPdfBytes), eq(DocumentType.SIGNED_PDF), eq(documentId), eq(documentNumber)))
                .thenReturn(storageUrl);

            // When/Then — empty chain must never silently produce a garbage certificate
            assertThatThrownBy(() -> service.signPdf(originalPdfUrl, documentId, documentNumber, padesLevel))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("empty certificate chain");
        }

        @Test
        @DisplayName("Should throw SigningException when certificate encoding fails")
        void shouldThrowOnCertificateEncodingException() throws java.security.cert.CertificateEncodingException {
            // Given
            String originalPdfUrl = "https://storage.example.com/original.pdf";
            String documentId = "doc-123";
            String documentNumber = "INV-2024-001";
            PadesLevel padesLevel = PadesLevel.BASELINE_B;

            byte[] pdfBytes = "test pdf content".getBytes();
            byte[] digest = new byte[32];
            byte[] signedPdfBytes = "signed pdf content".getBytes();
            String storageUrl = "https://storage.example.com/signed.pdf";

            X509Certificate badCert = mock(X509Certificate.class);
            when(badCert.getEncoded())
                .thenThrow(new java.security.cert.CertificateEncodingException("encoding failed"));

            when(mockDownloadPort.downloadPdf(originalPdfUrl)).thenReturn(pdfBytes);
            when(mockPdfPort.computeByteRangeDigest(pdfBytes)).thenReturn(digest);
            when(mockSigningPort.signPdfWithCertChain(pdfBytes, digest, padesLevel))
                .thenReturn(new SigningPort.SigningResult(signedPdfBytes, new X509Certificate[]{badCert}, null, null));
            when(mockStoragePort.store(eq(signedPdfBytes), eq(DocumentType.SIGNED_PDF), eq(documentId), eq(documentNumber)))
                .thenReturn(storageUrl);

            // When/Then
            assertThatThrownBy(() -> service.signPdf(originalPdfUrl, documentId, documentNumber, padesLevel))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Failed to encode certificate chain");
        }
    }

    @Nested
    @DisplayName("compensateSigning() method")
    class CompensateSigningMethod {

        @Test
        @DisplayName("Should successfully compensate by deleting signed PDF")
        void shouldCompensateSuccessfully() {
            // Given
            SignedPdfDocumentId documentId = SignedPdfDocumentId.generate();
            String storageUrl = "https://storage.example.com/signed.pdf";

            // When
            service.compensateSigning(documentId, storageUrl);

            // Then
            verify(mockStoragePort).delete(storageUrl);
            verify(mockRepository).deleteById(documentId);
        }

        @Test
        @DisplayName("Should handle null storage URL gracefully")
        void shouldHandleNullStorageUrl() {
            // Given
            SignedPdfDocumentId documentId = SignedPdfDocumentId.generate();

            // When
            service.compensateSigning(documentId, null);

            // Then - should not attempt to delete from storage
            verify(mockStoragePort, never()).delete(any());
            verify(mockRepository).deleteById(documentId);
        }

        @Test
        @DisplayName("Should handle empty storage URL by still calling delete")
        void shouldHandleEmptyStorageUrl() {
            // Given
            SignedPdfDocumentId documentId = SignedPdfDocumentId.generate();

            // When
            service.compensateSigning(documentId, "");

            // Then - delete is called with empty string, storage adapter handles it gracefully
            verify(mockStoragePort).delete("");
            verify(mockRepository).deleteById(documentId);
        }

        @Test
        @DisplayName("Should propagate storage exceptions")
        void shouldPropagateStorageExceptions() {
            // Given
            SignedPdfDocumentId documentId = SignedPdfDocumentId.generate();
            String storageUrl = "https://storage.example.com/signed.pdf";

            doThrow(new StorageException("Storage unavailable"))
                .when(mockStoragePort).delete(storageUrl);

            // When & Then
            assertThatThrownBy(() -> service.compensateSigning(documentId, storageUrl))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Storage unavailable");

            verify(mockStoragePort).delete(storageUrl);
        }
    }
}
