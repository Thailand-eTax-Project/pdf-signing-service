package com.wpanther.pdfsigning.application.usecase;

import com.wpanther.pdfsigning.application.dto.event.CompensatePdfSigningCommand;
import com.wpanther.pdfsigning.application.dto.event.ProcessPdfSigningCommand;
import com.wpanther.pdfsigning.domain.model.PadesLevel;
import com.wpanther.pdfsigning.domain.model.SignedPdfDocument;
import com.wpanther.pdfsigning.domain.model.SignedPdfDocumentId;
import com.wpanther.pdfsigning.application.port.out.PdfSignedEventPort;
import com.wpanther.pdfsigning.application.port.out.PdfSagaReplyPort;
import com.wpanther.pdfsigning.domain.repository.SignedPdfDocumentRepository;
import com.wpanther.pdfsigning.infrastructure.config.properties.PadesProperties;
import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SagaCommandHandler.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SagaCommandHandler Tests")
class SagaCommandHandlerTest {

    @Mock
    private SignedPdfDocumentRepository documentRepository;

    @Mock
    private DomainPdfSigningService domainPdfSigningService;

    @Mock
    private PdfSagaReplyPort sagaReplyPort;

    @Mock
    private PdfSignedEventPort pdfSignedEventPort;

    @Mock
    private PadesProperties padesProperties;

    @InjectMocks
    private SagaCommandHandler sagaCommandHandler;

    @BeforeEach
    void setUp() {
        lenient().when(padesProperties.getLevel()).thenReturn(PadesLevel.BASELINE_B);
    }

    @Test
    @DisplayName("Should process signing command successfully")
    void shouldProcessSigningCommandSuccessfully() {
        // Given
        ProcessPdfSigningCommand command = new ProcessPdfSigningCommand(
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "INV-2024-001", "INVOICE",
            "http://example.com/file.pdf", 12345L, true
        );

        SignedPdfDocument document = SignedPdfDocument.create(
            "doc-789", "INV-2024-001",
            "http://example.com/file.pdf", 12345L,
            "corr-456", "INVOICE"
        );

        when(documentRepository.findByDocumentId("doc-789")).thenReturn(Optional.empty());
        when(documentRepository.save(any(SignedPdfDocument.class))).thenAnswer(invocation -> {
            SignedPdfDocument doc = invocation.getArgument(0);
            // Simulate ID assignment by repository
            return doc;
        });

        DomainPdfSigningService.SignedPdfResult result = new DomainPdfSigningService.SignedPdfResult(
            "signed-file.pdf",
            "http://example.com/signed.pdf",
            54321L,
            "txn-abc",
            "PEM-CERT",
            "PAdES-BASELINE-B",
            Instant.now()
        );

        when(domainPdfSigningService.signPdf(
            eq("http://example.com/file.pdf"),
            anyString(),
            eq("INV-2024-001"),
            eq(PadesLevel.BASELINE_B)
        )).thenReturn(result);

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then
        verify(documentRepository, times(2)).save(any(SignedPdfDocument.class));
        verify(domainPdfSigningService).signPdf(
            eq("http://example.com/file.pdf"),
            anyString(),
            eq("INV-2024-001"),
            eq(PadesLevel.BASELINE_B)
        );
        verify(sagaReplyPort).publishSuccess(
            eq("saga-123"),
            eq(SagaStep.SIGN_PDF),
            eq("corr-456"),
            anyString(),
            eq("http://example.com/signed.pdf"),
            eq(54321L), eq("txn-abc"), eq("PEM-CERT"), eq("PAdES-BASELINE-B"), any()
        );
        verify(pdfSignedEventPort).publishPdfSignedNotification(
            eq("saga-123"), eq("doc-789"), eq("INV-2024-001"), eq("INVOICE"),
            anyString(),
            eq("http://example.com/signed.pdf"),
            eq(54321L), eq("PAdES-BASELINE-B"), any(), eq("corr-456")
        );
    }

    @Test
    @DisplayName("Should be idempotent for already completed documents")
    void shouldHandleAlreadyCompletedDocumentIdempotently() {
        // Given
        ProcessPdfSigningCommand command = new ProcessPdfSigningCommand(
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "INV-2024-001", "INVOICE",
            "http://example.com/file.pdf", 12345L, true
        );

        // Create a completed document using the factory method and state transitions
        SignedPdfDocument completedDocument = SignedPdfDocument.create(
            "doc-789", "INV-2024-001",
            "http://example.com/file.pdf", 12345L,
            "corr-456", "INVOICE"
        );
        completedDocument.startSigning();
        completedDocument.markCompleted(
            "signed-file.pdf",
            "http://example.com/signed.pdf",
            54321L,
            "txn-abc",
            "PEM-CERT",
            "PAdES-BASELINE-B",
            java.time.Instant.now()
        );

        when(documentRepository.findByDocumentId("doc-789")).thenReturn(Optional.of(completedDocument));

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then
        verify(domainPdfSigningService, never()).signPdf(any(), any(), any(), any());
        verify(sagaReplyPort).publishSuccess(
            eq("saga-123"),
            eq(SagaStep.SIGN_PDF),
            eq("corr-456"),
            anyString(),
            eq("http://example.com/signed.pdf"),
            eq(54321L), eq("txn-abc"), eq("PEM-CERT"), eq("PAdES-BASELINE-B"), any()
        );
        verify(pdfSignedEventPort).publishPdfSignedNotification(
            eq("saga-123"), eq("doc-789"), eq("INV-2024-001"), eq("INVOICE"),
            anyString(),
            eq("http://example.com/signed.pdf"),
            eq(54321L), eq("PAdES-BASELINE-B"), any(), eq("corr-456")
        );
    }

    @Test
    @DisplayName("Should handle compensation command")
    void shouldHandleCompensationCommand() {
        // Given
        CompensatePdfSigningCommand command = new CompensatePdfSigningCommand(
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "INVOICE", "sign-pdf"
        );

        SignedPdfDocument document = SignedPdfDocument.create(
            "doc-789", "INV-2024-001",
            "http://example.com/file.pdf", 12345L,
            "corr-456", "INVOICE"
        );
        document.startSigning();
        document.markCompleted(
            "signed-file.pdf",
            "http://example.com/signed.pdf",
            54321L,
            "txn-abc",
            "PEM-CERT",
            "PAdES-BASELINE-B",
            java.time.Instant.now()
        );

        when(documentRepository.findByDocumentId("doc-789")).thenReturn(Optional.of(document));
        doNothing().when(domainPdfSigningService).compensateSigning(any(SignedPdfDocumentId.class), anyString());

        // When
        sagaCommandHandler.handleCompensation(command);

        // Then
        verify(domainPdfSigningService).compensateSigning(eq(document.getId()), eq("http://example.com/signed.pdf"));
        verify(sagaReplyPort).publishCompensated(
            eq("saga-123"),
            eq(SagaStep.SIGN_PDF),
            eq("corr-456")
        );
    }

    @Test
    @DisplayName("Should handle compensation for non-existent document idempotently")
    void shouldHandleCompensationForNonExistentDocument() {
        // Given
        CompensatePdfSigningCommand command = new CompensatePdfSigningCommand(
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "INVOICE", "sign-pdf"
        );

        when(documentRepository.findByDocumentId("doc-789")).thenReturn(Optional.empty());

        // When
        sagaCommandHandler.handleCompensation(command);

        // Then
        verify(domainPdfSigningService, never()).compensateSigning(any(), any());
        verify(sagaReplyPort).publishCompensated(
            eq("saga-123"),
            eq(SagaStep.SIGN_PDF),
            eq("corr-456")
        );
    }

    @Test
    @DisplayName("Should call handleProcessCommand via SagaCommandPort interface")
    void shouldCallHandleProcessCommandViaInterface() {
        // Given - use the interface method
        ProcessPdfSigningCommand command = new ProcessPdfSigningCommand(
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "INV-2024-001", "INVOICE",
            "http://example.com/file.pdf", 12345L, true
        );

        SignedPdfDocument completedDocument = SignedPdfDocument.create(
            "doc-789", "INV-2024-001",
            "http://example.com/file.pdf", 12345L,
            "corr-456", "INVOICE"
        );
        completedDocument.startSigning();
        completedDocument.markCompleted(
            "signed-file.pdf",
            "http://example.com/signed.pdf",
            54321L,
            "txn-abc",
            "PEM-CERT",
            "PAdES-BASELINE-B",
            java.time.Instant.now()
        );

        when(documentRepository.findByDocumentId("doc-789")).thenReturn(Optional.of(completedDocument));

        // When - call via interface method
        sagaCommandHandler.handleProcessPdfSigning(command);

        // Then - should still work and publish success
        verify(sagaReplyPort).publishSuccess(
            eq("saga-123"),
            eq(SagaStep.SIGN_PDF),
            eq("corr-456"),
            anyString(),
            eq("http://example.com/signed.pdf"),
            eq(54321L), eq("txn-abc"), eq("PEM-CERT"), eq("PAdES-BASELINE-B"), any()
        );
        verify(pdfSignedEventPort).publishPdfSignedNotification(
            eq("saga-123"), eq("doc-789"), eq("INV-2024-001"), eq("INVOICE"),
            anyString(),
            eq("http://example.com/signed.pdf"),
            eq(54321L), eq("PAdES-BASELINE-B"), any(), eq("corr-456")
        );
    }

    @Test
    @DisplayName("Should call handleCompensation via SagaCommandPort interface")
    void shouldCallHandleCompensationViaInterface() {
        // Given - use the interface method
        CompensatePdfSigningCommand command = new CompensatePdfSigningCommand(
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "INVOICE", "sign-pdf"
        );

        when(documentRepository.findByDocumentId("doc-789")).thenReturn(Optional.empty());

        // When - call via interface method
        sagaCommandHandler.handleCompensatePdfSigning(command);

        // Then - should still work and publish compensated
        verify(sagaReplyPort).publishCompensated(
            eq("saga-123"),
            eq(SagaStep.SIGN_PDF),
            eq("corr-456")
        );
    }

    @Test
    @DisplayName("Should handle max retries exceeded scenario")
    void shouldHandleMaxRetriesExceeded() {
        // Given
        ProcessPdfSigningCommand command = new ProcessPdfSigningCommand(
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "INV-2024-001", "INVOICE",
            "http://example.com/file.pdf", 12345L, true
        );

        // Create a failed document with max retries
        SignedPdfDocument failedDocument = SignedPdfDocument.create(
            "doc-789", "INV-2024-001",
            "http://example.com/file.pdf", 12345L,
            "corr-456", "INVOICE"
        );
        failedDocument.startSigning();
        failedDocument.markFailed("Previous failure");
        // Increment to max retries (default is 3)
        failedDocument.incrementRetryCount();
        failedDocument.incrementRetryCount();
        failedDocument.incrementRetryCount();

        when(documentRepository.findByDocumentId("doc-789")).thenReturn(Optional.of(failedDocument));

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then - should publish failure with max retries message
        verify(sagaReplyPort).publishFailure(
            eq("saga-123"),
            eq(SagaStep.SIGN_PDF),
            eq("corr-456"),
            eq("Maximum retry attempts exceeded for PDF signing")
        );
        verify(pdfSignedEventPort).publishPdfSigningFailureNotification(
            eq("saga-123"), eq("doc-789"), eq("INV-2024-001"), eq("INVOICE"),
            eq("Maximum retry attempts exceeded for PDF signing"),
            eq("corr-456")
        );
        verify(domainPdfSigningService, never()).signPdf(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should handle signing service exception")
    void shouldHandleSigningServiceException() {
        // Given
        ProcessPdfSigningCommand command = new ProcessPdfSigningCommand(
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "INV-2024-001", "INVOICE",
            "http://example.com/file.pdf", 12345L, true
        );

        SignedPdfDocument document = SignedPdfDocument.create(
            "doc-789", "INV-2024-001",
            "http://example.com/file.pdf", 12345L,
            "corr-456", "INVOICE"
        );

        when(documentRepository.findByDocumentId("doc-789")).thenReturn(Optional.empty());
        when(documentRepository.save(any(SignedPdfDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(domainPdfSigningService.signPdf(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Signing failed"));

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then - should mark document as failed and publish failure event
        verify(sagaReplyPort).publishFailure(
            eq("saga-123"),
            eq(SagaStep.SIGN_PDF),
            eq("corr-456"),
            eq("Signing failed")
        );
        verify(pdfSignedEventPort).publishPdfSigningFailureNotification(
            eq("saga-123"), eq("doc-789"), eq("INV-2024-001"), eq("INVOICE"),
            eq("Signing failed"),
            eq("corr-456")
        );
    }

    @Test
    @DisplayName("Should handle exception during compensation")
    void shouldHandleCompensationException() {
        // Given
        CompensatePdfSigningCommand command = new CompensatePdfSigningCommand(
            "saga-123", SagaStep.SIGN_PDF, "corr-456",
            "doc-789", "INVOICE", "sign-pdf"
        );

        SignedPdfDocument document = SignedPdfDocument.create(
            "doc-789", "INV-2024-001",
            "http://example.com/file.pdf", 12345L,
            "corr-456", "INVOICE"
        );
        document.startSigning();
        document.markCompleted(
            "signed-file.pdf",
            "http://example.com/signed.pdf",
            54321L,
            "txn-abc",
            "PEM-CERT",
            "PAdES-BASELINE-B",
            java.time.Instant.now()
        );

        when(documentRepository.findByDocumentId("doc-789")).thenReturn(Optional.of(document));
        doThrow(new RuntimeException("Compensation failed"))
            .when(domainPdfSigningService).compensateSigning(any(SignedPdfDocumentId.class), anyString());

        // When
        sagaCommandHandler.handleCompensation(command);

        // Then - should publish failure event (saga reply only - no notification for compensation failure)
        verify(sagaReplyPort).publishFailure(
            eq("saga-123"),
            eq(SagaStep.SIGN_PDF),
            eq("corr-456"),
            eq("Compensation failed: Compensation failed")
        );
    }
}
