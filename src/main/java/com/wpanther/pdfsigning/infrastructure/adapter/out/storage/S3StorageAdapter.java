package com.wpanther.pdfsigning.infrastructure.adapter.out.storage;

import com.wpanther.pdfsigning.domain.model.DocumentType;
import com.wpanther.pdfsigning.domain.model.StorageException;
import com.wpanther.pdfsigning.application.port.out.DocumentStoragePort;
import com.wpanther.pdfsigning.infrastructure.config.properties.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Secondary adapter for AWS S3 / MinIO document storage.
 * <p>
 * Implements {@link DocumentStoragePort} using S3-compatible storage.
 * Stores files with key: {documentType}/YYYY/MM/DD/{documentType}-{documentId}.pdf
 * Returns presigned GET URLs so downstream services can download from private buckets.
 * </p>
 */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")
@Slf4j
public class S3StorageAdapter implements DocumentStoragePort {

    private final StorageProperties storageProperties;
    private S3Client s3Client;
    private S3Presigner s3Presigner;

    /**
     * Constructor for Spring-managed bean.
     * S3 client and presigner are initialized via @PostConstruct after properties are injected.
     */
    @Autowired
    public S3StorageAdapter(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    /**
     * Initializes the S3 client and presigner from configuration.
     * Called once by the Spring container after property injection.
     */
    @PostConstruct
    public void init() {
        StorageProperties.S3 s3 = storageProperties.getS3();

        AwsBasicCredentials credentials = AwsBasicCredentials.create(
            s3.getAccessKey(),
            s3.getSecretKey()
        );
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(credentials);
        Region region = Region.of(s3.getRegion());

        var s3Builder = S3Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider);

        var presignerBuilder = S3Presigner.builder()
            .region(region)
            .credentialsProvider(credentialsProvider);

        if (s3.getEndpoint() != null && !s3.getEndpoint().isEmpty()) {
            URI endpoint = URI.create(s3.getEndpoint());
            s3Builder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
            log.info("Using custom S3 endpoint: {}", s3.getEndpoint());
        }

        if (s3.isPathStyleAccess()) {
            s3Builder.forcePathStyle(true);
            log.info("Using path-style access for S3");
        }

        this.s3Client = s3Builder.build();
        this.s3Presigner = presignerBuilder.build();

        log.info("Initialized S3 document storage adapter: bucket={}, region={}, presignedUrlTtlMinutes={}",
            s3.getBucketName(), s3.getRegion(), s3.getPresignedUrlTtlMinutes());
    }

    /**
     * Constructor for testing with injected S3Client and S3Presigner.
     * Package-private for test access only.
     * This bypasses @PostConstruct initialization for unit testing.
     */
    S3StorageAdapter(StorageProperties storageProperties, S3Client s3Client, S3Presigner s3Presigner) {
        this.storageProperties = storageProperties;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    @Override
    public String store(byte[] documentData, DocumentType documentType, String documentId, String documentNumber) {
        try {
            StorageProperties.S3 s3 = storageProperties.getS3();
            String key = generateKey(documentType, documentId, documentNumber);

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(s3.getBucketName())
                .key(key)
                .contentType("application/pdf")
                .contentLength((long) documentData.length)
                .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(documentData));

            String url = generatePresignedUrl(key);
            log.info("Stored document in S3: type={}, bucket={}, key={}, size={} bytes",
                documentType, s3.getBucketName(), key, documentData.length);

            return url;

        } catch (StorageException e) {
            throw e;
        } catch (S3Exception e) {
            log.error("Failed to store document in S3", e);
            throw new StorageException("Failed to store document in S3: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error storing document in S3", e);
            throw new StorageException("Failed to store document in S3: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] retrieve(String storageUrl) {
        try {
            StorageProperties.S3 s3 = storageProperties.getS3();
            String key = extractKeyFromUrl(storageUrl);

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3.getBucketName())
                .key(key)
                .build();

            try (var response = s3Client.getObject(getObjectRequest)) {
                return response.readAllBytes();
            }

        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error retrieving document from S3", e);
            throw new StorageException("Failed to retrieve document: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String storageUrl) {
        try {
            StorageProperties.S3 s3 = storageProperties.getS3();
            String key = extractKeyFromUrl(storageUrl);

            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(s3.getBucketName())
                .key(key)
                .build();

            s3Client.deleteObject(deleteObjectRequest);
            log.info("Deleted document from S3: bucket={}, key={}", s3.getBucketName(), key);

        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error deleting document from S3", e);
            throw new StorageException("Failed to delete document: " + e.getMessage(), e);
        }
    }

    /**
     * Generate S3 key: {documentType}/YYYY/MM/DD/{documentType}-{documentId}-{documentNumber}.pdf
     */
    private String generateKey(DocumentType documentType, String documentId, String documentNumber) {
        LocalDate now = LocalDate.now();
        String sanitizedType = documentType.getValue().toLowerCase().replace("_", "-");
        return String.format("%s/%04d/%02d/%02d/%s-%s-%s.pdf",
            sanitizedType, now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
            sanitizedType, documentId, documentNumber);
    }

    /**
     * Generate a presigned GET URL for the given S3 key.
     * The TTL is taken from {@code app.storage.s3.presigned-url-ttl-minutes}.
     */
    private String generatePresignedUrl(String key) {
        StorageProperties.S3 s3 = storageProperties.getS3();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(s3.getPresignedUrlTtlMinutes()))
            .getObjectRequest(r -> r.bucket(s3.getBucketName()).key(key))
            .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Extract S3 key from a storage URL.
     *
     * @param storageUrl URL returned by {@link #store} (presigned GET or full path)
     * @return the S3 object key
     * @throws StorageException if the URL is not from the configured bucket or cannot be parsed
     */
    private String extractKeyFromUrl(String storageUrl) {
        String bucketName = storageProperties.getS3().getBucketName();
        String pathPart = storageUrl.contains("?")
            ? storageUrl.substring(0, storageUrl.indexOf("?"))
            : storageUrl;

        // Path-style: http://localhost:9100/etax-signed-pdfs/signed-pdf/...
        int bucketIndex = pathPart.indexOf("/" + bucketName + "/");
        if (bucketIndex >= 0) {
            return pathPart.substring(bucketIndex + bucketName.length() + 2);
        }

        // Virtual-hosted-style: http://etax-signed-pdfs.localhost:9100/signed-pdf/...
        URI uri = URI.create(pathPart);
        if (uri.getHost() != null && uri.getHost().startsWith(bucketName + ".")) {
            String path = uri.getPath();
            if (path != null && path.startsWith("/")) {
                return path.substring(1);
            }
        }

        throw new StorageException(
            "Storage URL is not from the configured bucket '"
            + bucketName + "': " + storageUrl);
    }
}
