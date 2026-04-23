package com.wpanther.pdfsigning.integration;

import com.wpanther.pdfsigning.application.usecase.DomainPdfSigningService;
import com.wpanther.pdfsigning.domain.model.PadesLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: calls {@link DomainPdfSigningService#signPdf} directly
 * against the real eidasremotesigning CSC API and MinIO — no Kafka, no saga.
 *
 * <p>Purpose: verify the CSC signing pipeline (authorize → signHash → CMS embed → S3 store)
 * produces a valid signed PDF, isolating this component from the full Kafka/saga flow.</p>
 *
 * <p>Run:
 * <pre>
 *   mvn test -Pintegration -Dtest="SignPdfSmokeIntegrationTest" -Dintegration.tests.enabled=true
 * </pre>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.bootstrap-servers=${KAFKA_BROKERS:localhost:9093}",
                "KAFKA_BROKERS=localhost:9093"
        }
)
@ActiveProfiles("full-integration-test")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
public class SignPdfSmokeIntegrationTest extends AbstractFullIntegrationTest {

    @Autowired
    private DomainPdfSigningService domainPdfSigningService;

    @BeforeEach
    @Override
    void cleanDatabase() {
        // Skip DB clean — this test doesn't write to signed_pdf_documents
        if (sagaReplyKafkaConsumer != null) {
            sagaReplyKafkaConsumer.poll(java.time.Duration.ofSeconds(1));
        }
    }

    @Test
    void shouldSignTaxInvoicePdfAndStoreInMinIO() {
        String documentId = "SMOKE-" + UUID.randomUUID();
        String pdfUrl = taxInvoicePdfUrl;
        long pdfSize = taxInvoicePdfSize;

        // Act
        DomainPdfSigningService.SignedPdfResult result = domainPdfSigningService.signPdf(
                pdfUrl, documentId, "SMOKE-TEST", PadesLevel.BASELINE_B);

        // Assert: signed PDF stored in MinIO
        assertThat(result.signedPdfUrl()).as("signedPdfUrl should not be blank").isNotBlank();
        assertThat(result.signedPdfSize()).as("signedPdfSize should be positive").isGreaterThan(0L);
        assertThat(result.certificate()).as("certificate should start with PEM header")
                .startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(result.transactionId()).as("transactionId should not be blank").isNotBlank();
        assertThat(result.signatureLevel()).isEqualTo("PAdES-BASELINE-B");

        // Verify the signed PDF exists in MinIO
        boolean exists = objectExistsInMinIO(result.signedPdfUrl());
        assertThat(exists).as("signed PDF should exist in MinIO").isTrue();

        // Download and verify PDF header
        byte[] signedPdfBytes = downloadFromMinIO(result.signedPdfUrl());
        assertThat(signedPdfBytes).as("signed PDF should not be empty").isNotEmpty();
        assertThat(signedPdfBytes[0]).isEqualTo((byte) '%');
        assertThat(signedPdfBytes[1]).isEqualTo((byte) 'P');
        assertThat(signedPdfBytes[2]).isEqualTo((byte) 'D');
        assertThat(signedPdfBytes[3]).isEqualTo((byte) 'F');
        assertThat(signedPdfBytes[4]).isEqualTo((byte) '-');

        // Verify PAdES signature marker (ByteRange dictionary entry)
        String signedPdfStr = new String(signedPdfBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(signedPdfStr).as("signed PDF should contain /ByteRange PAdES marker")
                .contains("/ByteRange");

        System.out.printf("Signed PDF smoke test PASSED:%n");
        System.out.printf("  documentId:  %s%n", documentId);
        System.out.printf("  originalSize: %d bytes%n", pdfSize);
        System.out.printf("  signedSize:   %d bytes%n", result.signedPdfSize());
        System.out.printf("  signedPdfUrl: %s%n", result.signedPdfUrl());
        System.out.printf("  cert length:  %d chars%n", result.certificate().length());
    }
}
