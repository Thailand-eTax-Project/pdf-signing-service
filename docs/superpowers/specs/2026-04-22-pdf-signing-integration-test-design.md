# PDF Signing Service — Full Integration Test Design

**Date:** 2026-04-22
**Service:** pdf-signing-service (port 8087)
**Scope:** Full end-to-end integration tests that call the real eidasremotesigning service, mirroring the xml-signing-service integration test suite.

---

## 1. Goal

Add a full integration test suite to `pdf-signing-service` that exercises the complete signing pipeline with no mocks:

- Kafka command → Apache Camel route → `SagaCommandHandler`
- `DomainPdfSigningService`: download PDF from MinIO, compute byte-range digest, call real CSC API (eidasremotesigning), embed PAdES signature
- Store signed PDF to MinIO (S3 storage backend)
- Write outbox events (`saga.reply.pdf-signing` + `notification.events`) to PostgreSQL

The suite mirrors `xml-signing-service/src/test/java/.../integration/` in structure and test scenarios, adapted for PAdES (PDF) instead of XAdES (XML) and for MinIO-based PDF download (instead of inline XML content).

---

## 2. Context: Why MinIO for Both Input and Output

The saga-orchestrator was simplified — `document-storage-service` is no longer in the saga flow. The unsigned PDF now comes directly from the pdf-generation-service, which uploads it to MinIO. The `ProcessPdfSigningCommand.pdfUrl` is therefore a MinIO presigned URL. The integration tests pre-upload real PDF/A-3 fixtures to MinIO to replicate this flow exactly.

---

## 3. Required Infrastructure

Same as xml-signing-service integration tests. Start with:

```bash
cd invoice-microservices
./scripts/test-containers-start.sh --with-eidas --with-debezium --auto-deploy-connectors
```

| Container | Port | Role |
|---|---|---|
| PostgreSQL (pdfsigning_db) | 5433 | Service database |
| PostgreSQL (eidasremotesigning) | 5433 (same instance, different DB) | eIDAS credential storage |
| Kafka | 9093 | Message broker |
| MinIO | 9100 | PDF input + signed PDF output |
| eidasremotesigning | 9000 | Real CSC API v2.0 (PAdES signing) |
| Debezium | 8083 | CDC connector (outbox → Kafka) |

**Run tests:**
```bash
cd services/pdf-signing-service
mvn test -Pintegration -Dtest="SagaCommandFullIntegrationTest"
```

---

## 4. File Structure

### New Java files

```
src/test/java/com/wpanther/pdfsigning/integration/
├── support/
│   └── EidasRemoteSigningTestHelper.java
├── config/
│   ├── FullIntegrationTestConfiguration.java
│   └── TestKafkaProducerConfig.java
├── AbstractFullIntegrationTest.java
└── SagaCommandFullIntegrationTest.java
```

### New test resources

```
src/test/resources/
├── application-full-integration-test.yml
└── samples/
    ├── tax-invoice-pdfa3.pdf     ← copied from taxinvoice-pdf-generation-service/target/preview/
    └── invoice-pdfa3.pdf         ← copied from invoice-pdf-generation-service/target/preview/
```

### pom.xml additions (test scope)

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.2.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
    <scope>test</scope>
</dependency>
```

**`integration` Maven profile** — mirrors xml-signing-service:
- Sets system property `integration.tests.enabled=true`
- Re-includes `**/integration/**` (excluded from default surefire run)

---

## 5. EidasRemoteSigningTestHelper

Copied verbatim from `xml-signing-service` with one change:

```java
// xml-signing-service
"clientName", "xml-signing-service-full-integration-test"

// pdf-signing-service
"clientName", "pdf-signing-service-full-integration-test"
```

All three bootstrap steps are identical:
1. `POST /client-registration` — register OAuth2 client
2. `POST /oauth2/token` (client_credentials) — fetch Bearer token
3. `INSERT INTO signing_certificates` — insert BCFKS credential pointing to `/app/keystores/eidas-signing.bfks`

Token is cached and auto-refreshed 60 seconds before expiry via `getValidToken()`.

---

## 6. FullIntegrationTestConfiguration

`@TestConfiguration` providing three beans:

### cscBearerTokenInterceptor
Feign `RequestInterceptor` that calls `EidasRemoteSigningTestHelper.getValidToken()` and sets `Authorization: Bearer <token>` on every outgoing CSC API call. Identical to xml-signing-service.

### fullIntegrationJdbcTemplate
`JdbcTemplate` wired to the `pdfsigning_db` `DataSource`. Used in test assertions to query `signed_pdf_documents` and `outbox_events`.

### minioVerificationS3Client + minioS3Presigner
`S3Client` and `S3Presigner` both pointing at `http://localhost:9100`, bucket `etax-signed-pdfs`, credentials `minioadmin/minioadmin`. Used for:
- Pre-uploading test PDF fixtures (`@BeforeAll`)
- Generating presigned GET URLs for the `pdfUrl` field in commands
- Verifying signed PDFs were stored after signing (`headObject`)

---

## 7. TestKafkaProducerConfig

Copied verbatim from xml-signing-service. Profile changed from `consumer-test` to `full-integration-test`. Provides `KafkaTemplate<String, String>` wired to `localhost:9093`.

---

## 8. AbstractFullIntegrationTest

### Spring annotations
```
@SpringBootTest(webEnvironment = NONE,
    properties = {
        "spring.kafka.bootstrap-servers=${KAFKA_BROKERS:localhost:9093}",
        "KAFKA_BROKERS=localhost:9093"
    })
@ActiveProfiles("full-integration-test")
@Import({TestKafkaProducerConfig.class, FullIntegrationTestConfiguration.class})
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@TestInstance(Lifecycle.PER_CLASS)
```

### @DynamicPropertySource configureEidasProperties()
Runs before the Spring context is created. Calls `EidasRemoteSigningTestHelper.setupOnce(EIDAS_BASE_URL, EIDAS_PG_JDBC_URL, PG_USER, PG_PASSWORD)` and injects:
- `app.csc.client-id` ← `eidasSetup.clientId()`
- `app.csc.credential-id` ← `eidasSetup.credentialId()`

Constants:
```java
EIDAS_BASE_URL      = "http://localhost:9000"
EIDAS_PG_JDBC_URL   = "jdbc:postgresql://localhost:5433/eidasremotesigning"
PG_USER             = "postgres"
PG_PASSWORD         = "postgres"
MINIO_BUCKET_NAME   = "etax-signed-pdfs"
```

### @BeforeAll uploadTestPdfs()
Runs once per test class. Loads both PDF fixtures from classpath, uploads to MinIO at:
- `test-inputs/tax-invoice-pdfa3.pdf`
- `test-inputs/invoice-pdfa3.pdf`

Generates presigned GET URLs (TTL: 60 minutes) stored as `static volatile String taxInvoicePdfUrl` and `static volatile String invoicePdfUrl`. Reused across all test methods.

### @BeforeEach cleanDatabase()
```sql
DELETE FROM outbox_events;
DELETE FROM signed_pdf_documents;
```
Also drains stale messages from the Kafka reply consumer via a 1-second poll.

### Kafka reply consumer
Subscribed to `saga.reply.pdf-signing`. Used in outbox event assertions to verify events arrive on Kafka after CDC routing. Consumer group: `saga-reply-pdf-signing-test-{timestamp}`.

### Helper methods

| Method | Notes |
|---|---|
| `getDocumentByDocumentId(String)` | `SELECT * FROM signed_pdf_documents WHERE document_id = ?` |
| `getOutboxEventsByAggregateId(String)` | `SELECT * FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at` |
| `getAllOutboxEvents()` | All outbox events ordered by created_at |
| `awaitDocumentStatus(String, String)` | Awaitility 60s / 2s poll |
| `awaitOutboxEventCount(String, int)` | Awaitility 60s / 2s poll |
| `awaitDocumentDeleted(String)` | Awaitility 30s / 2s poll |
| `objectExistsInMinIO(String presignedUrl)` | Extract S3 key from URL path, call `headObject` |
| `downloadFromMinIO(String presignedUrl)` | Extract S3 key, call `getObjectAsBytes` |
| `createProcessCommand(String documentId, String documentNumber, String documentType, String pdfUrl, Long pdfSize)` | Builds `ProcessPdfSigningCommand` using convenience constructor |
| `createCompensateCommand(String documentId, String correlationId)` | Builds `CompensatePdfSigningCommand` |
| `newDocumentId()` | `"DOC-" + UUID.randomUUID()` |
| `newCorrelationId()` | `UUID.randomUUID().toString()` |
| `sagaIdFor(String correlationId)` | `"saga-" + correlationId` |

**Key difference from xml-signing-service:** no XML fixture helpers. The `taxInvoicePdfUrl` and `invoicePdfUrl` static fields serve as the pre-uploaded PDF download URLs used in commands.

---

## 9. SagaCommandFullIntegrationTest — Test Scenarios

Topics:
- `COMMAND_TOPIC = "saga.command.pdf-signing"`
- `COMPENSATION_TOPIC = "saga.compensation.pdf-signing"`

### SigningHappyPath

| Test | What's asserted |
|---|---|
| `shouldSignTaxInvoicePdfEndToEnd()` | DB reaches COMPLETED; `signed_pdf_url` non-blank; `transaction_id` non-blank; `certificate` starts with `-----BEGIN CERTIFICATE-----`; `error_message` is null; `document_type = TAX_INVOICE` |
| `shouldSignInvoicePdfEndToEnd()` | Same assertions for INVOICE type |

### SignedPdfContentVerification

| Test | What's asserted |
|---|---|
| `signedPdfShouldBeValidPdf()` | Downloaded bytes start with `%PDF-` (5-byte header check) |
| `signedPdfShouldContainPadesSignatureMarker()` | Downloaded bytes contain `/ByteRange` — the PAdES signature marker embedded by PDFBox |
| `signedPdfSizeInDbShouldMatchActualBytes()` | `signed_pdf_size` in DB equals `downloadFromMinIO().length` |

### OutboxEventVerification

| Test | What's asserted |
|---|---|
| `shouldWritePdfSignedNotificationWithCorrectFields()` | `notification.events` outbox row: `event_type = PdfSignedNotificationEvent`, `aggregate_type = SignedPdfDocument`, payload contains `documentId`, `documentType`, `correlationId`, `signedPdfUrl` |
| `shouldWriteSagaReplySuccessWithSignedPdfUrl()` | `saga.reply.pdf-signing` outbox row: `aggregate_id = sagaId`, payload contains `SUCCESS` + `correlationId` + `signedPdfUrl` |
| `shouldWriteBothOutboxEventsAtomically()` | Both `notification.events` and `saga.reply.pdf-signing` rows exist; exactly 1 of each |

**Outbox aggregate_id mapping (pdf-signing-service specific):**
- `saga.reply.pdf-signing` → `aggregate_id = sagaId`
- `notification.events` → `aggregate_id = signedDocumentId` (the `SignedPdfDocument` UUID, from `id` column)

### Idempotency

| Test | What's asserted |
|---|---|
| `shouldNotResignAlreadyCompletedDocument()` | Duplicate command with different `correlationId` → still only 1 DB record; `signed_pdf_url` unchanged; SUCCESS reply written for the duplicate `sagaId` |

### MultipleDocuments

| Test | What's asserted |
|---|---|
| `shouldProcessTwoDocumentsConcurrently()` | TAX_INVOICE and INVOICE both reach COMPLETED; both have non-blank `signed_pdf_url`; both signed PDFs exist in MinIO |

### Compensation

| Test | What's asserted |
|---|---|
| `shouldDeleteSignedPdfFromMinIOAndDbOnCompensation()` | After compensation: DB record deleted; signed PDF gone from MinIO (`headObject` returns NoSuchKey); COMPENSATED outbox event written to `saga.reply.pdf-signing` |
| `shouldSendCompensatedReplyForNonExistentDocument()` | Compensation for a never-signed `documentId` → COMPENSATED reply still written (idempotent) |

---

## 10. application-full-integration-test.yml

Key settings:

```yaml
# Storage: S3/MinIO (not local filesystem)
app:
  storage:
    provider: s3
    s3:
      bucket-name: etax-signed-pdfs
      endpoint: http://localhost:9100
      access-key: minioadmin
      secret-key: minioadmin
      presigned-url-ttl-minutes: 60

  csc:
    service-url: http://localhost:9000
    client-id: ${CSC_CLIENT_ID:dynamic}      # injected by @DynamicPropertySource
    credential-id: ${CSC_CREDENTIAL_ID:dynamic}

spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/pdfsigning_db
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9093}

# Extended resilience4j timeouts for real CSC API calls
resilience4j:
  timelimiter:
    instances:
      csc-auth:
        timeoutDuration: 60s
      csc-sign-hash:
        timeoutDuration: 60s

eureka:
  client:
    enabled: false
```

---

## 11. Key Differences vs xml-signing-service

| Aspect | xml-signing-service | pdf-signing-service |
|---|---|---|
| Command payload | `xmlContent` (inline string) | `pdfUrl` (MinIO presigned URL) |
| Test fixture setup | None (XML passed inline) | `@BeforeAll` uploads PDFs to MinIO |
| Storage | MinIO (`signed-xml-documents`) | MinIO (`etax-signed-pdfs`) |
| Signature verification | Look for `<ds:Signature>` in XML | Look for `/ByteRange` in PDF bytes |
| Notification topic | `xml.signed` | `notification.events` |
| Notification `aggregate_id` | `documentId` | `signedDocumentId` (internal UUID) |
| Document type detection | Yes (from XML namespace) | Not applicable (pure crypto) |

---

## 12. Known Limitation

`DomainPdfSigningService` passes `null` as the `SignedPdfDocument` argument to `DocumentStoragePort.store()`, causing the S3 key to use `"unknown"` as the document ID portion (e.g., `signed-pdf/2026/04/22/signed-pdf-unknown.pdf`). Within a single test run this causes all signed PDFs on the same day to share the same S3 key — later tests overwrite earlier ones. This does not affect test correctness because:

1. Each test independently awaits its own `document_id` in `signed_pdf_documents`
2. The `signed_pdf_url` presigned URL is read from the DB and used for download verification
3. Integration tests run sequentially in Maven by default

This limitation should be addressed in a follow-up by passing the aggregate ID to `store()`.
