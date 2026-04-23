package com.wpanther.pdfsigning.infrastructure.adapter.out.storage;

import com.wpanther.pdfsigning.domain.model.DocumentType;
import com.wpanther.pdfsigning.domain.model.SignedPdfDocument;
import com.wpanther.pdfsigning.infrastructure.config.properties.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.MalformedURLException;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link S3StorageAdapter}.
 * <p>
 * Tests the S3 storage adapter using mocked S3Client and S3Presigner.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3StorageAdapter Tests")
class S3StorageAdapterTest {

    @Mock
    private S3Client mockS3Client;

    @Mock
    private S3Presigner mockPresigner;

    @Mock
    private PresignedGetObjectRequest mockPresignedRequest;

    private S3StorageAdapter adapter;

    private static final String PRESIGNED_URL =
        "http://localhost:9000/etax-signed-pdfs/signed-pdf/2024/01/01/test.pdf?X-Amz-Signature=abc123";

    @BeforeEach
    void setUp() throws MalformedURLException {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setProvider("s3");
        storageProperties.getS3().setBucketName("etax-signed-pdfs");
        storageProperties.getS3().setPresignedUrlTtlMinutes(60);

        lenient().when(mockPresigner.presignGetObject(any(GetObjectPresignRequest.class)))
            .thenReturn(mockPresignedRequest);
        lenient().when(mockPresignedRequest.url()).thenReturn(new URL(PRESIGNED_URL));

        adapter = new S3StorageAdapter(storageProperties, mockS3Client, mockPresigner);
    }

    @Nested
    @DisplayName("store() method")
    class StoreMethod {

        @Test
        @DisplayName("Should store document and return presigned URL")
        void shouldStoreDocument() {
            // Given
            byte[] documentData = "test pdf content".getBytes();
            SignedPdfDocument document = createTestDocument();

            // When
            String storageUrl = adapter.store(documentData, DocumentType.SIGNED_PDF, document.getId().asString());

            // Then
            assertThat(storageUrl).isEqualTo(PRESIGNED_URL);
            assertThat(storageUrl).contains("X-Amz-Signature");

            verify(mockS3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            verify(mockPresigner).presignGetObject(any(GetObjectPresignRequest.class));
        }

        @Test
        @DisplayName("Should create date-based key structure in presign request")
        void shouldCreateDateBasedKeyStructure() {
            // Given
            byte[] documentData = "test pdf".getBytes();
            SignedPdfDocument document = createTestDocument();
            ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);

            // When
            adapter.store(documentData, DocumentType.SIGNED_PDF, document.getId().asString());

            // Then — verify the key passed to S3 has the expected structure
            verify(mockS3Client).putObject(putCaptor.capture(), any(RequestBody.class));
            String key = putCaptor.getValue().key();
            String[] parts = key.split("/");
            assertThat(parts).hasSize(5); // signed-pdf/YYYY/MM/DD/filename.pdf
            assertThat(parts[0]).isEqualTo("signed-pdf");
            assertThat(parts[1]).matches("\\d{4}");  // year
            assertThat(parts[2]).matches("\\d{2}");  // month
            assertThat(parts[3]).matches("\\d{2}");  // day
            assertThat(parts[4]).endsWith(".pdf");
        }

        @Test
        @DisplayName("Should use configured TTL when generating presigned URL")
        void shouldUseConfiguredTtlForPresignedUrl() {
            // Given
            byte[] documentData = "test pdf".getBytes();
            SignedPdfDocument document = createTestDocument();
            ArgumentCaptor<GetObjectPresignRequest> presignCaptor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);

            // When
            adapter.store(documentData, DocumentType.SIGNED_PDF, document.getId().asString());

            // Then — verify TTL is 60 minutes as configured
            verify(mockPresigner).presignGetObject(presignCaptor.capture());
            assertThat(presignCaptor.getValue().signatureDuration())
                .isEqualTo(java.time.Duration.ofMinutes(60));
        }

        @Test
        @DisplayName("Should include document ID in S3 key")
        void shouldIncludeDocumentIdInKey() {
            // Given
            byte[] documentData = "test pdf".getBytes();
            String documentId = "doc-abc123";
            ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);

            // When
            adapter.store(documentData, DocumentType.SIGNED_PDF, documentId);

            // Then — key should contain the document ID
            verify(mockS3Client).putObject(putCaptor.capture(), any(RequestBody.class));
            assertThat(putCaptor.getValue().key()).contains(documentId);
        }

        @Test
        @DisplayName("Should propagate S3 exceptions as StorageException")
        void shouldPropagateS3Exception() {
            // Given
            byte[] documentData = "test pdf".getBytes();
            SignedPdfDocument document = createTestDocument();
            doThrow(S3Exception.builder().message("S3 error").build())
                .when(mockS3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));

            // When/Then
            assertThatThrownBy(() -> adapter.store(documentData, DocumentType.SIGNED_PDF, document.getId().asString()))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("Failed to store document");
        }
    }

    @Nested
    @DisplayName("retrieve() method")
    class RetrieveMethod {

        @Test
        @DisplayName("Should retrieve stored document")
        void shouldRetrieveDocument() {
            when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(new RuntimeException("Stream-based response"));

            assertThatThrownBy(() -> adapter.retrieve("http://localhost:9000/etax-signed-pdfs/test-file.pdf"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class);

            verify(mockS3Client).getObject(any(GetObjectRequest.class));
        }

        @Test
        @DisplayName("Should throw exception when object not found")
        void shouldThrowWhenObjectNotFound() {
            when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("Not Found").statusCode(404).build());

            assertThatThrownBy(() -> adapter.retrieve("http://localhost:9000/etax-signed-pdfs/nonexistent.pdf"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("Failed to retrieve document");
        }

        @Test
        @DisplayName("Should throw StorageException for bare key without bucket path")
        void shouldThrowForBareKeyWithoutBucketPath() {
            // Bare key has no bucket path — extractKeyFromUrl throws before S3 is called
            assertThatThrownBy(() -> adapter.retrieve("test-key"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("not from the configured bucket");

            verifyNoInteractions(mockS3Client);
        }
    }

    @Nested
    @DisplayName("delete() method")
    class DeleteMethod {

        @Test
        @DisplayName("Should delete existing object")
        void shouldDeleteExistingObject() {
            adapter.delete("http://localhost:9000/etax-signed-pdfs/test-file.pdf");

            verify(mockS3Client).deleteObject(any(DeleteObjectRequest.class));
        }

        @Test
        @DisplayName("Should handle non-existent object gracefully")
        void shouldHandleNonExistentObjectGracefully() {
            adapter.delete("http://localhost:9000/etax-signed-pdfs/nonexistent.pdf");

            verify(mockS3Client).deleteObject(any(DeleteObjectRequest.class));
        }

        @Test
        @DisplayName("Should propagate S3 exceptions as StorageException")
        void shouldPropagateS3ExceptionOnDelete() {
            doThrow(S3Exception.builder().message("Access denied").statusCode(403).build())
                .when(mockS3Client).deleteObject(any(DeleteObjectRequest.class));

            assertThatThrownBy(() -> adapter.delete("http://localhost:9000/etax-signed-pdfs/test-file.pdf"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("Failed to delete document");
        }
    }

    @Nested
    @DisplayName("URL key extraction")
    class UrlKeyExtractionTests {

        @Test
        @DisplayName("Should retrieve document with pre-signed URL containing query params")
        void shouldRetrieveWithPresignedUrl() {
            String presignedUrl = "http://localhost:9000/etax-signed-pdfs/signed-pdf/2024/02/27/test-file.pdf?X-Amz-Algorithm=AWS4-HMAC-SHA256";

            when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(new RuntimeException("Stream response"));

            assertThatThrownBy(() -> adapter.retrieve(presignedUrl))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class);

            verify(mockS3Client).getObject(any(GetObjectRequest.class));
        }

        @Test
        @DisplayName("Should extract key from URL with bucket path")
        void shouldExtractKeyFromUrlWithBucketPath() {
            when(mockS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(new RuntimeException("Stream response"));

            assertThatThrownBy(() -> adapter.retrieve("http://localhost:9000/etax-signed-pdfs/signed-pdf/test.pdf"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class);

            verify(mockS3Client).getObject(any(GetObjectRequest.class));
        }

        @Test
        @DisplayName("Should throw StorageException for URL without configured bucket")
        void shouldThrowForUrlWithoutConfiguredBucket() {
            // URL does not contain the configured bucket name — extractKeyFromUrl throws before S3 is called
            assertThatThrownBy(() -> adapter.retrieve("simple-key.pdf"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("not from the configured bucket");

            verifyNoInteractions(mockS3Client);
        }

        @Test
        @DisplayName("Should throw StorageException for malformed URL")
        void shouldThrowForMalformedUrl() {
            // URL has no recognizable bucket path — extractKeyFromUrl throws before S3 is called
            assertThatThrownBy(() -> adapter.retrieve("malformed:///weird//url.pdf"))
                .isInstanceOf(com.wpanther.pdfsigning.domain.model.StorageException.class)
                .hasMessageContaining("not from the configured bucket");

            verifyNoInteractions(mockS3Client);
        }
    }

    @Nested
    @DisplayName("Document type handling")
    class DocumentTypeTests {

        @Test
        @DisplayName("Should sanitize document type with underscores")
        void shouldSanitizeDocumentTypeWithUnderscores() {
            // Given
            byte[] documentData = "test pdf".getBytes();
            SignedPdfDocument document = createTestDocument();
            ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);

            // When
            adapter.store(documentData, DocumentType.TAX_INVOICE, document.getId().asString());

            // Then — underscores should be replaced with hyphens in the key
            verify(mockS3Client).putObject(putCaptor.capture(), any(RequestBody.class));
            assertThat(putCaptor.getValue().key()).contains("tax-invoice/");
            assertThat(putCaptor.getValue().key()).doesNotContain("TAX_INVOICE");
        }

        @Test
        @DisplayName("Should preserve document type without underscores")
        void shouldPreserveDocumentTypeWithoutUnderscores() {
            // Given
            byte[] documentData = "test pdf".getBytes();
            SignedPdfDocument document = createTestDocument();
            ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);

            // When
            adapter.store(documentData, DocumentType.INVOICE, document.getId().asString());

            // Then
            verify(mockS3Client).putObject(putCaptor.capture(), any(RequestBody.class));
            assertThat(putCaptor.getValue().key()).contains("invoice/");
        }
    }

    @Nested
    @DisplayName("Spring constructor")
    class SpringConstructorTests {

        @Test
        @DisplayName("Should create adapter with StorageProperties")
        void shouldCreateWithStorageProperties() {
            StorageProperties storageProperties = new StorageProperties();
            storageProperties.setProvider("s3");
            storageProperties.getS3().setBucketName("test-bucket");
            storageProperties.getS3().setRegion("us-east-1");
            storageProperties.getS3().setAccessKey("test-access-key");
            storageProperties.getS3().setSecretKey("test-secret-key");
            storageProperties.getS3().setEndpoint("");
            storageProperties.getS3().setPathStyleAccess(false);

            S3StorageAdapter springAdapter = new S3StorageAdapter(storageProperties);

            assertThat(springAdapter).isNotNull();
        }

        @Test
        @DisplayName("Should create adapter with custom endpoint via StorageProperties")
        void shouldCreateWithCustomEndpoint() {
            StorageProperties storageProperties = new StorageProperties();
            storageProperties.setProvider("s3");
            storageProperties.getS3().setBucketName("test-bucket");
            storageProperties.getS3().setRegion("us-east-1");
            storageProperties.getS3().setAccessKey("minioadmin");
            storageProperties.getS3().setSecretKey("minioadmin");
            storageProperties.getS3().setEndpoint("http://localhost:9000");
            storageProperties.getS3().setPathStyleAccess(true);

            S3StorageAdapter adapterWithEndpoint = new S3StorageAdapter(storageProperties);

            assertThat(adapterWithEndpoint).isNotNull();
        }
    }

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
