package com.wpanther.pdfsigning.integration.config;

import com.wpanther.pdfsigning.integration.support.EidasRemoteSigningTestHelper;
import feign.RequestInterceptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Spring test configuration for full integration tests.
 *
 * <p>Provides four beans absent from the production application context:
 * <ol>
 *   <li><strong>cscBearerTokenInterceptor</strong> — Feign interceptor that adds
 *       {@code Authorization: Bearer <token>} to every CSC API call.</li>
 *   <li><strong>fullIntegrationJdbcTemplate</strong> — {@link JdbcTemplate} for test DB assertions.</li>
 *   <li><strong>minioVerificationS3Client</strong> — {@link S3Client} pointing at MinIO on localhost:9100.</li>
 *   <li><strong>minioS3Presigner</strong> — {@link S3Presigner} for generating presigned URLs for test PDF upload.</li>
 * </ol>
 */
@TestConfiguration
@Import(TestKafkaProducerConfig.class)
public class FullIntegrationTestConfiguration {

    @Bean
    public RequestInterceptor cscBearerTokenInterceptor() {
        return requestTemplate -> {
            String token = EidasRemoteSigningTestHelper.getValidToken();
            if (token != null && !token.isBlank()) {
                requestTemplate.header("Authorization", "Bearer " + token);
            }
        };
    }

    @Bean("fullIntegrationJdbcTemplate")
    public JdbcTemplate fullIntegrationJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("minioVerificationS3Client")
    public S3Client minioVerificationS3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .endpointOverride(URI.create("http://localhost:9100"))
                .forcePathStyle(true)
                .build();
    }

    @Bean("minioS3Presigner")
    public S3Presigner minioS3Presigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .endpointOverride(URI.create("http://localhost:9100"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
