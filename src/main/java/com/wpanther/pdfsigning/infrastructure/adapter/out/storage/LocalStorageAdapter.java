package com.wpanther.pdfsigning.infrastructure.adapter.out.storage;

import com.wpanther.pdfsigning.domain.model.DocumentType;
import com.wpanther.pdfsigning.domain.model.StorageException;
import com.wpanther.pdfsigning.application.port.out.DocumentStoragePort;
import com.wpanther.pdfsigning.infrastructure.config.properties.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

/**
 * Secondary adapter for local filesystem document storage.
 * <p>
 * Implements {@link DocumentStoragePort} using the local filesystem.
 * Stores files in {basePath}/YYYY/MM/DD/signed-pdf-{documentId}.pdf
 * </p>
 */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class LocalStorageAdapter implements DocumentStoragePort {

    private final StorageProperties storageProperties;

    @Override
    public String store(byte[] documentData, DocumentType documentType, String documentId, String documentNumber) {
        try {
            String basePath = storageProperties.getLocal().getBasePath();
            String baseUrl = storageProperties.getLocal().getBaseUrl();

            LocalDateTime now = LocalDateTime.now();
            String year = String.format("%04d", now.getYear());
            String month = String.format("%02d", now.getMonthValue());
            String day = String.format("%02d", now.getDayOfMonth());

            Path directory = Paths.get(basePath, year, month, day);
            Files.createDirectories(directory);

            String filename = String.format("%s-%s-%s.pdf",
                documentType.getValue().toLowerCase(), documentId, documentNumber);
            Path filePath = directory.resolve(filename);

            Files.write(filePath, documentData);

            String relativePath = Paths.get(basePath).relativize(filePath).toString();
            String url = baseUrl + "/documents/" + relativePath.replace("\\", "/");

            log.info("Stored document locally: type={}, path={}, size={} bytes", documentType, filePath, documentData.length);
            return url;

        } catch (StorageException e) {
            // Re-throw domain exceptions as-is
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            log.error("Unexpected error storing document to local filesystem", e);
            throw new StorageException("Failed to store document to local filesystem: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] retrieve(String storageUrl) {
        try {
            String basePath = storageProperties.getLocal().getBasePath();
            String baseUrl = storageProperties.getLocal().getBaseUrl();

            // Guard against mismatched storage backend URLs
            String documentsPrefix = baseUrl + "/documents/";
            if (!storageUrl.startsWith(documentsPrefix)) {
                throw new StorageException(
                    "Storage URL does not match local storage configuration: " + storageUrl);
            }

            // Convert URL back to filesystem path using known prefix length
            String relativeUrl = storageUrl.substring(documentsPrefix.length());
            Path filePath = Paths.get(basePath, relativeUrl.replace("/", java.io.File.separator));

            if (!Files.exists(filePath)) {
                throw new StorageException("Document not found: " + storageUrl);
            }

            byte[] content = Files.readAllBytes(filePath);
            log.debug("Retrieved document from local storage: {} bytes", content.length);
            return content;

        } catch (StorageException e) {
            // Re-throw domain exceptions as-is
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            log.error("Unexpected error retrieving document from local filesystem", e);
            throw new StorageException("Failed to retrieve document: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String storageUrl) {
        try {
            String basePath = storageProperties.getLocal().getBasePath();
            String baseUrl = storageProperties.getLocal().getBaseUrl();

            // Guard: only accept URLs from this storage instance
            String documentsPrefix = baseUrl + "/documents/";
            if (!storageUrl.startsWith(documentsPrefix)) {
                throw new StorageException(
                    "Storage URL does not match local storage configuration: " + storageUrl);
            }

            // Convert URL back to filesystem path
            String relativeUrl = storageUrl.substring(documentsPrefix.length());
            Path filePath = Paths.get(basePath, relativeUrl.replace("/", java.io.File.separator));

            Files.deleteIfExists(filePath);
            log.info("Deleted document from local storage: {}", filePath);

        } catch (StorageException e) {
            // Re-throw domain exceptions as-is
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            log.error("Unexpected error deleting document from local filesystem", e);
            throw new StorageException("Failed to delete document: " + e.getMessage(), e);
        }
    }
}
