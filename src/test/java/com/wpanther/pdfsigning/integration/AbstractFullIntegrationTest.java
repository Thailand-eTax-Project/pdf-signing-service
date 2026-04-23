package com.wpanther.pdfsigning.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.pdfsigning.application.dto.event.CompensatePdfSigningCommand;
import com.wpanther.pdfsigning.application.dto.event.ProcessPdfSigningCommand;
import com.wpanther.pdfsigning.integration.config.FullIntegrationTestConfiguration;
import com.wpanther.pdfsigning.integration.config.TestKafkaProducerConfig;
import com.wpanther.pdfsigning.integration.support.EidasRemoteSigningTestHelper;
import com.wpanther.saga.domain.enums.SagaStep;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

/**
 * Base class for full end-to-end integration tests.
 *
 * <p>No mocks — all dependencies are real:
 * <ul>
 *   <li>eidasremotesigning CSC API on {@code localhost:9000}</li>
 *   <li>MinIO on {@code localhost:9100} (PDF input + signed PDF output)</li>
 *   <li>PostgreSQL on {@code localhost:5433} (pdfsigning_db)</li>
 *   <li>Kafka on {@code localhost:9093}</li>
 * </ul>
 *
 * <p>Start required containers before running:
 * <pre>
 *   cd invoice-microservices
 *   ./scripts/test-containers-start.sh --with-eidas --with-debezium --auto-deploy-connectors
 * </pre>
 *
 * <p>Run tests:
 * <pre>
 *   cd services/pdf-signing-service
 *   mvn test -Pintegration -Dtest="SagaCommandFullIntegrationTest"
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
@Import({TestKafkaProducerConfig.class, FullIntegrationTestConfiguration.class})
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractFullIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractFullIntegrationTest.class);

    private static final String TEST_RUN_ID = String.valueOf(System.currentTimeMillis());

    private static final String EIDAS_BASE_URL = "http://localhost:9000";
    private static final String EIDAS_PG_JDBC_URL = "jdbc:postgresql://localhost:5433/eidasremotesigning";
    private static final String PG_USER = "postgres";
    private static final String PG_PASSWORD = "postgres";

    protected static final String MINIO_BUCKET_NAME = "etax-signed-pdfs";
    protected static final String SAGA_REPLY_TOPIC = "saga.reply.pdf-signing";

    protected static volatile EidasRemoteSigningTestHelper.SetupResult eidasSetup;
    protected static volatile String taxInvoicePdfUrl;
    protected static volatile String invoicePdfUrl;
    protected static volatile Long taxInvoicePdfSize;
    protected static volatile Long invoicePdfSize;

    @Autowired
    protected KafkaTemplate<String, String> testKafkaTemplate;

    @Autowired
    @Qualifier("fullIntegrationJdbcTemplate")
    protected JdbcTemplate testJdbcTemplate;

    @Autowired
    @Qualifier("minioVerificationS3Client")
    protected S3Client minioVerificationS3Client;

    @Autowired
    @Qualifier("minioS3Presigner")
    protected S3Presigner minioS3Presigner;

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrapServers;

    @Value("${app.kafka.command-consumer-group}")
    private String commandConsumerGroup;

    @Value("${app.kafka.compensation-consumer-group}")
    private String compensationConsumerGroup;

    protected ObjectMapper objectMapper;
    protected KafkaConsumer<String, String> sagaReplyKafkaConsumer;

    /**
     * Runs before the Spring context is created.
     * Bootstraps eidasremotesigning and injects CSC client-id + credential-id dynamically.
     */
    @DynamicPropertySource
    static void configureEidasProperties(DynamicPropertyRegistry registry) {
        if (!"true".equals(System.getProperty("integration.tests.enabled"))) {
            registry.add("app.csc.client-id", () -> "integration-not-enabled");
            registry.add("app.csc.credential-id", () -> "integration-not-enabled");
            return;
        }
        try {
            eidasSetup = EidasRemoteSigningTestHelper.setupOnce(
                    EIDAS_BASE_URL, EIDAS_PG_JDBC_URL, PG_USER, PG_PASSWORD);
            registry.add("app.csc.client-id", () -> eidasSetup.clientId());
            registry.add("app.csc.credential-id", () -> eidasSetup.credentialId());

            // Unique consumer groups per JVM so Camel starts from latest with no stale offsets
            registry.add("app.kafka.command-consumer-group",
                    () -> "pdf-signing-test-cmd-" + TEST_RUN_ID);
            registry.add("app.kafka.compensation-consumer-group",
                    () -> "pdf-signing-test-comp-" + TEST_RUN_ID);

            log.info("[AbstractFullIntegrationTest] eIDAS setup complete: clientId={}, credentialId={}",
                    eidasSetup.clientId(), eidasSetup.credentialId());
        } catch (Exception e) {
            log.error("[AbstractFullIntegrationTest] eIDAS setup failed: {}", e.getMessage());
            registry.add("app.csc.client-id", () -> "eidas-setup-failed");
            registry.add("app.csc.credential-id", () -> "eidas-setup-failed");
        }
    }

    @BeforeAll
    void setUpObjectMapper() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @BeforeAll
    void setUpSagaReplyConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                "saga-reply-pdf-signing-test-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        sagaReplyKafkaConsumer = new KafkaConsumer<>(props);
        sagaReplyKafkaConsumer.subscribe(List.of(SAGA_REPLY_TOPIC));
    }

    /**
     * Waits for the Camel Kafka consumers to register with Kafka and get partition
     * assignment. Without this, {@code autoOffsetReset=latest} can land the start
     * offset AFTER the first test message if the consumer hasn't been assigned yet.
     */
    @BeforeAll
    void waitForCamelConsumersReady() {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        try (AdminClient admin = AdminClient.create(adminProps)) {
            await().atMost(30, TimeUnit.SECONDS)
                    .pollInterval(2, TimeUnit.SECONDS)
                    .until(() -> {
                        var desc = admin.describeConsumerGroups(
                                List.of(commandConsumerGroup, compensationConsumerGroup))
                                .all().get(3, TimeUnit.SECONDS);
                        boolean cmdReady = desc.get(commandConsumerGroup) != null
                                && !desc.get(commandConsumerGroup).members().isEmpty();
                        boolean compReady = desc.get(compensationConsumerGroup) != null
                                && !desc.get(compensationConsumerGroup).members().isEmpty();
                        return cmdReady && compReady;
                    });
            log.info("[AbstractFullIntegrationTest] Camel consumers ready: cmd={}, comp={}",
                    commandConsumerGroup, compensationConsumerGroup);
        }
    }

    /**
     * Uploads the two test PDF fixtures to MinIO and stores presigned URLs as static fields.
     * Runs once per test class — skipped if URLs are already populated from a previous class.
     */
    @BeforeAll
    void uploadTestPdfs() throws Exception {
        if (!"true".equals(System.getProperty("integration.tests.enabled"))) {
            return;
        }
        if (taxInvoicePdfUrl != null) {
            return;
        }

        // Create bucket if it doesn't exist (409 = already exists)
        try {
            minioVerificationS3Client.createBucket(b -> b.bucket(MINIO_BUCKET_NAME));
            log.info("[AbstractFullIntegrationTest] Created MinIO bucket: {}", MINIO_BUCKET_NAME);
        } catch (S3Exception e) {
            if (e.statusCode() != 409) {
                throw e;
            }
            log.info("[AbstractFullIntegrationTest] MinIO bucket already exists: {}", MINIO_BUCKET_NAME);
        }

        byte[] taxInvoiceBytes = loadSamplePdf("tax-invoice-pdfa3.pdf");
        taxInvoicePdfUrl = uploadAndPresign("test-inputs/tax-invoice-pdfa3.pdf", taxInvoiceBytes);
        taxInvoicePdfSize = (long) taxInvoiceBytes.length;

        byte[] invoiceBytes = loadSamplePdf("invoice-pdfa3.pdf");
        invoicePdfUrl = uploadAndPresign("test-inputs/invoice-pdfa3.pdf", invoiceBytes);
        invoicePdfSize = (long) invoiceBytes.length;

        log.info("[AbstractFullIntegrationTest] Test PDFs uploaded: taxInvoice={} bytes, invoice={} bytes",
                taxInvoicePdfSize, invoicePdfSize);
    }

    @BeforeEach
    void cleanDatabase() {
        testJdbcTemplate.execute("DELETE FROM outbox_events");
        testJdbcTemplate.execute("DELETE FROM signed_pdf_documents");
        if (sagaReplyKafkaConsumer != null) {
            sagaReplyKafkaConsumer.poll(Duration.ofSeconds(1));
        }
    }

    // ----- DB helpers -----

    protected Map<String, Object> getDocumentByDocumentId(String documentId) {
        List<Map<String, Object>> results = testJdbcTemplate.queryForList(
                "SELECT * FROM signed_pdf_documents WHERE document_id = ?", documentId);
        return results.isEmpty() ? null : results.get(0);
    }

    protected List<Map<String, Object>> getOutboxEventsByAggregateId(String aggregateId) {
        return testJdbcTemplate.queryForList(
                "SELECT * FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at",
                aggregateId);
    }

    protected List<Map<String, Object>> getAllOutboxEvents() {
        return testJdbcTemplate.queryForList(
                "SELECT * FROM outbox_events ORDER BY created_at");
    }

    // ----- Await helpers -----

    protected Map<String, Object> awaitDocumentStatus(String documentId, String expectedStatus) {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> {
                    Map<String, Object> doc = getDocumentByDocumentId(documentId);
                    return doc != null && expectedStatus.equals(doc.get("status"));
                });
        return getDocumentByDocumentId(documentId);
    }

    protected void awaitOutboxEventCount(String aggregateId, int expectedCount) {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> getOutboxEventsByAggregateId(aggregateId).size() >= expectedCount);
    }

    protected void awaitDocumentDeleted(String documentId) {
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> getDocumentByDocumentId(documentId) == null);
    }

    // ----- MinIO helpers -----

    /**
     * Returns true if the object pointed to by {@code presignedUrl} exists in MinIO.
     * Extracts the S3 key from the URL path (strips query string + bucket prefix).
     */
    protected boolean objectExistsInMinIO(String presignedUrl) {
        try {
            minioVerificationS3Client.headObject(HeadObjectRequest.builder()
                    .bucket(MINIO_BUCKET_NAME)
                    .key(extractKeyFromPresignedUrl(presignedUrl))
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * Downloads and returns the bytes at {@code presignedUrl} from MinIO.
     */
    protected byte[] downloadFromMinIO(String presignedUrl) {
        return minioVerificationS3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(MINIO_BUCKET_NAME)
                .key(extractKeyFromPresignedUrl(presignedUrl))
                .build()).asByteArray();
    }

    private String extractKeyFromPresignedUrl(String presignedUrl) {
        String pathPart = presignedUrl.contains("?")
                ? presignedUrl.substring(0, presignedUrl.indexOf("?"))
                : presignedUrl;

        // Path-style: http://localhost:9100/etax-signed-pdfs/signed-pdf/...
        int bucketIndex = pathPart.indexOf("/" + MINIO_BUCKET_NAME + "/");
        if (bucketIndex >= 0) {
            return pathPart.substring(bucketIndex + MINIO_BUCKET_NAME.length() + 2);
        }

        // Virtual-hosted-style: http://etax-signed-pdfs.localhost:9100/signed-pdf/...
        String path = java.net.URI.create(pathPart).getPath();
        if (path != null && path.startsWith("/")) {
            return path.substring(1);
        }

        throw new IllegalArgumentException(
                "Cannot extract S3 key from URL: " + presignedUrl);
    }

    // ----- Kafka helpers -----

    protected void sendEvent(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            testKafkaTemplate.send(topic, key, json).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send event to topic: " + topic, e);
        }
    }

    // ----- Command factories -----

    protected ProcessPdfSigningCommand createProcessCommand(
            String documentId, String documentNumber, String documentType,
            String pdfUrl, Long pdfSize, String correlationId) {
        String sagaId = sagaIdFor(correlationId);
        return new ProcessPdfSigningCommand(
                sagaId, SagaStep.SIGN_PDF, correlationId,
                documentId, documentNumber, documentType, pdfUrl, pdfSize, true);
    }

    protected CompensatePdfSigningCommand createCompensateCommand(
            String documentId, String correlationId) {
        String sagaId = sagaIdFor(correlationId);
        return new CompensatePdfSigningCommand(
                sagaId, SagaStep.SIGN_PDF, correlationId,
                documentId, "TAX_INVOICE", SagaStep.SIGN_PDF.name());
    }

    // ----- ID generators -----

    protected String newDocumentId() {
        return "DOC-" + UUID.randomUUID();
    }

    protected String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    protected String sagaIdFor(String correlationId) {
        return "saga-" + correlationId;
    }

    // ----- Private MinIO upload -----

    private byte[] loadSamplePdf(String filename) throws Exception {
        try (java.io.InputStream is = getClass().getResourceAsStream("/samples/" + filename)) {
            if (is == null) {
                throw new IllegalStateException("Sample PDF not found on classpath: /samples/" + filename);
            }
            return is.readAllBytes();
        }
    }

    private String uploadAndPresign(String key, byte[] pdfBytes) {
        minioVerificationS3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(MINIO_BUCKET_NAME)
                        .key(key)
                        .contentType("application/pdf")
                        .contentLength((long) pdfBytes.length)
                        .build(),
                RequestBody.fromBytes(pdfBytes));

        return minioS3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(2))
                .getObjectRequest(r -> r.bucket(MINIO_BUCKET_NAME).key(key))
                .build()).url().toString();
    }
}
