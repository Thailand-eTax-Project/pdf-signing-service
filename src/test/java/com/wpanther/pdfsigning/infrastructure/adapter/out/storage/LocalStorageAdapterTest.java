package com.wpanther.pdfsigning.infrastructure.adapter.out.storage;

import com.wpanther.pdfsigning.domain.model.DocumentType;
import com.wpanther.pdfsigning.domain.model.SignedPdfDocument;
import com.wpanther.pdfsigning.infrastructure.config.properties.StorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LocalStorageAdapter}.
 * <p>
 * Tests the local filesystem storage adapter using temporary directories.
 * </p>
 */
@DisplayName("LocalStorageAdapter Tests")
class LocalStorageAdapterTest {

    @TempDir
    Path tempDir;

    private LocalStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setProvider("local");
        storageProperties.getLocal().setBasePath(tempDir.toString());
        storageProperties.getLocal().setBaseUrl("http://localhost:8080");

        adapter = new LocalStorageAdapter(storageProperties);
    }

    @Nested
    @DisplayName("store() method")
    class StoreMethod {

        @Test
        @DisplayName("Should store document and return URL")
        void shouldStoreDocument() throws IOException {
            // Given
            byte[] documentData = "test pdf content".getBytes();
            SignedPdfDocument document = createTestDocument();

            // When
            String storageUrl = adapter.store(documentData, DocumentType.SIGNED_PDF, document.getId().asString(), "INV-2024-001");

            // Then
            assertThat(storageUrl).isNotNull();
            assertThat(storageUrl).startsWith("http://localhost:8080");
            assertThat(storageUrl).contains(".pdf");

            // Verify file was created by listing files in tempDir
            try (var files = Files.walk(tempDir)) {
                assertThat(files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".pdf"))
                    .count()).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("Should create date-based directory structure")
        void shouldCreateDateBasedDirectories() {
            // Given
            byte[] documentData = "test pdf".getBytes();
            SignedPdfDocument document = createTestDocument();

            // When
            String storageUrl = adapter.store(documentData, DocumentType.SIGNED_PDF, document.getId().asString(), "INV-2024-001");

            // Then - should have YYYY/MM/DD structure
            String relativePath = storageUrl.substring("http://localhost:8080/documents".length());
            String[] parts = relativePath.split("/");
            assertThat(parts).hasSize(5); // documents/YYYY/MM/DD/filename.pdf
            assertThat(parts[1]).matches("\\d{4}"); // year
            assertThat(parts[2]).matches("\\d{2}");   // month
            assertThat(parts[3]).matches("\\d{2}");   // day
        }

        @Test
        @DisplayName("Should include document ID in storage URL")
        void shouldIncludeDocumentIdInUrl() {
            // Given
            byte[] documentData = "test pdf".getBytes();
            String documentId = "doc-abc123";

            // When
            String documentNumber = "DOC-ABC-123";
            String storageUrl = adapter.store(documentData, DocumentType.SIGNED_PDF, documentId, documentNumber);

            // Then
            assertThat(storageUrl).contains(documentId);
            assertThat(storageUrl).contains(documentNumber);
        }

        @Test
        @DisplayName("Should throw exception when storage fails")
        void shouldThrowExceptionWhenStorageFails() {
            // Given - create adapter with invalid basePath that will fail
            StorageProperties failingStorageProperties = new StorageProperties();
            failingStorageProperties.setProvider("local");
            failingStorageProperties.getLocal().setBasePath("/nonexistent/path/that/does/not/exist/and/cannot/be/created/1234567890");
            failingStorageProperties.getLocal().setBaseUrl("http://localhost:8080");

            LocalStorageAdapter failingAdapter = new LocalStorageAdapter(failingStorageProperties);

            byte[] documentData = "test pdf".getBytes();
            SignedPdfDocument document = createTestDocument();

            // When/Then - should wrap exception in StorageException
            assertThatThrownBy(() -> failingAdapter.store(documentData, DocumentType.SIGNED_PDF, document.getId().asString(), "INV-2024-001"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("Failed to store document to local filesystem");
        }
    }

    @Nested
    @DisplayName("retrieve() method")
    class RetrieveMethod {

        @Test
        @DisplayName("Should retrieve stored document")
        void shouldRetrieveDocument() throws IOException {
            // Given
            byte[] originalData = "test pdf content".getBytes();
            SignedPdfDocument document = createTestDocument();
            Path testFile = tempDir.resolve("test-file.pdf");
            Files.write(testFile, originalData);

            // When
            byte[] retrieved = adapter.retrieve("http://localhost:8080/documents/test-file.pdf");

            // Then
            assertThat(retrieved).isEqualTo(originalData);
        }

        @Test
        @DisplayName("Should throw exception when file not found")
        void shouldThrowWhenFileNotFound() {
            // When/Then
            assertThatThrownBy(() -> adapter.retrieve("http://localhost:8080/documents/nonexistent.pdf"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("Should extract key from storage URL correctly")
        void shouldExtractKeyFromUrl() {
            // Given
            byte[] originalData = "test pdf".getBytes();
            SignedPdfDocument document = createTestDocument();

            // When
            String storageUrl = adapter.store(originalData, DocumentType.SIGNED_PDF, document.getId().asString(), "INV-2024-001");

            // When/Then
            byte[] retrieved = adapter.retrieve(storageUrl);
            assertThat(retrieved).isEqualTo(originalData);
        }

        @Test
        @DisplayName("Should throw exception on URL with different base")
        void shouldThrowExceptionOnUrlWithDifferentBase() {
            // Given - URL from different base (will cause substring to go negative)
            // Using a URL that starts with baseUrl but is shorter than expected
            String shortUrl = "http:/";

            // When/Then
            assertThatThrownBy(() -> adapter.retrieve(shortUrl))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class);
        }
    }

    @Nested
    @DisplayName("delete() method")
    class DeleteMethod {

        @Test
        @DisplayName("Should delete existing file")
        void shouldDeleteExistingFile() throws IOException {
            // Given
            byte[] documentData = "test pdf".getBytes();
            SignedPdfDocument document = createTestDocument();
            Path testFile = tempDir.resolve("test-file.pdf");
            Files.write(testFile, documentData);

            // When
            adapter.delete("http://localhost:8080/documents/test-file.pdf");

            // Then
            assertThat(Files.exists(testFile)).isFalse();
        }

        @Test
        @DisplayName("Should handle deletion of non-existent file gracefully")
        void shouldHandleNonExistentFileGracefully() {
            // When/Then - should not throw
            adapter.delete("http://localhost:8080/documents/nonexistent.pdf");
            // Passes if no exception is thrown
        }

        @Test
        @DisplayName("Should throw when URL does not match local storage configuration")
        void shouldThrowWhenUrlDoesNotMatchStorage() {
            // When/Then - direct path not accepted; must be a URL from this storage instance
            assertThatThrownBy(() -> adapter.delete("/var/signed-documents/2024/01/01/signed-pdf-123.pdf"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("does not match local storage configuration");
        }

        @Test
        @DisplayName("Should throw StorageException for URL not from this storage instance")
        void shouldThrowForUrlNotFromThisStorage() {
            // When/Then - rejects URLs from other storage instances
            assertThatThrownBy(() -> adapter.delete("http://other-host/storage/doc.pdf"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("does not match local storage configuration");
        }
    }

    /**
     * Helper to create a test SignedPdfDocument.
     */
    private SignedPdfDocument createTestDocument() {
        return SignedPdfDocument.create(
            "invoice-123",
            "INV-2024-001",
            "http://example.com/original.pdf",
            1024L,
            "corr-456",
            "TAX_INVOICE"
        );
    }
}
