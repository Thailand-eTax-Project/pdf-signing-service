package com.wpanther.pdfsigning.application.usecase;

import com.wpanther.pdfsigning.domain.model.*;
import com.wpanther.pdfsigning.domain.repository.SignedPdfDocumentRepository;
import com.wpanther.pdfsigning.application.port.out.DocumentDownloadPort;
import com.wpanther.pdfsigning.application.port.out.DocumentStoragePort;
import com.wpanther.pdfsigning.application.port.out.PdfGenerationPort;
import com.wpanther.pdfsigning.application.port.out.SigningPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Core domain service for PDF signing operations.
 * <p>
 * This service contains the business logic for PDF signing,
 * depending only on port interfaces (no infrastructure dependencies).
 * </p>
 * <p>
 * Dependencies are injected via constructor, enabling easy testing
 * with mock implementations of the ports.
 * </p>
 */
@Service
public class DomainPdfSigningService {

    private static final Logger log = LoggerFactory.getLogger(DomainPdfSigningService.class);

    private final SigningPort signingPort;
    private final PdfGenerationPort pdfPort;
    private final DocumentStoragePort storagePort;
    private final DocumentDownloadPort downloadPort;
    private final SignedPdfDocumentRepository repository;

    /**
     * Result of a PDF signing operation.
     * <p>
     * Contains all information needed by the application layer
     * to update the aggregate and publish events.
     * </p>
     */
    public record SignedPdfResult(
        String signedPdfPath,
        String signedPdfUrl,
        Long signedPdfSize,
        String transactionId,
        String certificate,
        String signatureLevel,
        Instant signatureTimestamp
    ) {}

    /**
     * Creates a new domain PDF signing service.
     *
     * @param signingPort   Port for signing operations (CSC, mock, etc.)
     * @param pdfPort       Port for PDF-specific processing (digest, embedding)
     * @param storagePort   Port for document storage
     * @param downloadPort  Port for downloading PDF documents
     * @param repository    Repository for persisting signed documents
     */
    public DomainPdfSigningService(SigningPort signingPort,
                                    PdfGenerationPort pdfPort,
                                    DocumentStoragePort storagePort,
                                    DocumentDownloadPort downloadPort,
                                    SignedPdfDocumentRepository repository) {
        this.signingPort = signingPort;
        this.pdfPort = pdfPort;
        this.storagePort = storagePort;
        this.downloadPort = downloadPort;
        this.repository = repository;
    }

    /**
     * Sign a PDF document with PAdES signature.
     * <p>
     * This method orchestrates the signing workflow using hexagonal ports:
     * <ol>
     *   <li>Downloads PDF via {@link DocumentDownloadPort}</li>
     *   <li>Computes byte range digest via {@link PdfGenerationPort}</li>
     *   <li>Validates certificate chain via {@link SigningPort}</li>
     *   <li>Signs PDF via {@link SigningPort}</li>
     *   <li>Stores signed PDF via {@link DocumentStoragePort}</li>
     * </ol>
     * </p>
     *
     * @param originalPdfUrl URL of the unsigned PDF
     * @param documentId     Unique identifier for this signing operation
     * @param padesLevel     Desired PAdES conformance level
     * @return SignedPdfResult containing all signing details
     * @throws SigningException if signing fails
     */
    public SignedPdfResult signPdf(String originalPdfUrl,
                                     String documentId,
                                     String documentNumber,
                                     PadesLevel padesLevel) {
        log.info("Starting PDF signing for document: {}", documentId);

        // Step 1: Download PDF
        log.debug("Downloading PDF from URL: {}", originalPdfUrl);
        byte[] pdfBytes = downloadPort.downloadPdf(originalPdfUrl);
        log.debug("Downloaded PDF: {} bytes", pdfBytes.length);

        // Step 2: Compute byte range digest
        log.debug("Computing PDF byte range digest");
        byte[] digest = pdfPort.computeByteRangeDigest(pdfBytes);
        log.debug("Computed digest: {} bytes", digest.length);

        // Step 3: Sign the PDF (includes certificate validation)
        log.debug("Signing PDF with PAdES level: {}", padesLevel);
        SigningPort.SigningResult signingResult = signingPort.signPdfWithCertChain(pdfBytes, digest, padesLevel);
        log.debug("Signed PDF: {} bytes", signingResult.signedPdf().length);

        // Step 4: Store the signed PDF
        log.debug("Storing signed PDF");
        String storageUrl = storagePort.store(
            signingResult.signedPdf(),
            DocumentType.SIGNED_PDF,
            documentId,
            documentNumber
        );
        String storagePath = extractPathFromUrl(storageUrl);
        log.debug("Stored signed PDF at: {}", storageUrl);

        // Step 5: Build result
        // transactionId from CSC signHash response enables audit traceability to CSC service logs.
        // Falls back to local UUID if CSC does not provide a responseID (responseID is optional
        // in CSC API v2.0 signHash response — not all CSC providers return it).
        String transactionId = signingResult.transactionId() != null
            ? signingResult.transactionId()
            : UUID.randomUUID().toString();
        String certificatePem = extractCertificatePem(signingResult.certificateChain());
        // Use trusted timestamp from CSC/TSA when available (PAdES-B-T and higher).
        // For PAdES-B-B (no TSA), falls back to local clock — acceptable since there is
        // no HSM-issued timestamp in that case.
        Instant signatureTimestamp = signingResult.timestamp() != null
            ? signingResult.timestamp()
            : Instant.now();

        log.info("PDF signing completed for document: {}", documentId);

        return new SignedPdfResult(
            storagePath,
            storageUrl,
            (long) signingResult.signedPdf().length,
            transactionId,
            certificatePem,
            padesLevel.getCode(),
            signatureTimestamp
        );
    }

    /**
     * Compensate/rollback a signing operation by deleting the signed PDF.
     *
     * @param documentId ID of the document to compensate
     * @param storageUrl URL of the signed PDF to delete
     * @throws SigningException if document not found
     */
    public void compensateSigning(SignedPdfDocumentId documentId, String storageUrl) {
        log.info("Compensating signing for document: {}", documentId);

        if (storageUrl != null) {
            storagePort.delete(storageUrl);
            log.info("Deleted signed PDF from storage: {}", storageUrl);
        }

        repository.deleteById(documentId);
        log.info("Signing compensation completed for document: {}", documentId);
    }

    /**
     * Extract the filename (last path segment) from a storage URL using proper URI parsing.
     *
     * <p>The URL is already validated by the storage adapter that produced it
     * (LocalStorageAdapter or S3StorageAdapter). This method uses URI to safely
     * extract the last path segment regardless of URL format variations
     * (presigned query params, double slashes, encoded chars).</p>
     *
     * @param storageUrl URL returned by DocumentStoragePort.store()
     * @return the filename portion of the URL
     */
    private String extractPathFromUrl(String storageUrl) {
        try {
            URI uri = URI.create(storageUrl);
            String path = uri.getPath();

            if (path == null || path.isEmpty()) {
                return storageUrl;
            }

            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                return path.substring(lastSlash + 1);
            }
            return path;
        } catch (Exception e) {
            // Fallback — storage URL was already validated by storage adapter
            int lastSlash = storageUrl.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < storageUrl.length() - 1) {
                return storageUrl.substring(lastSlash + 1);
            }
            return storageUrl;
        }
    }

    /**
     * Extract PEM-encoded certificate from certificate chain.
     * <p>
     * Converts X509Certificate[] to PEM format using Base64 encoding.
     * Includes all certificates in the chain.
     * </p>
     *
     * @param certChain Certificate chain from signing result
     * @return PEM-encoded certificate string
     * @throws SigningException if the chain is empty or certificate encoding fails
     */
    private String extractCertificatePem(X509Certificate[] certChain) {
        if (certChain == null || certChain.length == 0) {
            throw new SigningException("CSC API returned empty certificate chain");
        }

        try {
            StringBuilder pem = new StringBuilder();
            Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[]{'\r', '\n'});

            for (X509Certificate cert : certChain) {
                pem.append("-----BEGIN CERTIFICATE-----\n");
                byte[] der = cert.getEncoded();
                pem.append(encoder.encodeToString(der));
                pem.append("\n-----END CERTIFICATE-----\n");
            }

            return pem.toString();
        } catch (java.security.cert.CertificateEncodingException e) {
            // This is a real error - certificate encoding failed
            log.error("Failed to encode certificate to PEM", e);
            throw new SigningException("Failed to encode certificate chain to PEM format: " + e.getMessage(), e);
        }
    }
}
