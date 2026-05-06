# PDF Signing Service

Microservice for digitally signing PDF documents using PAdES (PDF Advanced Electronic Signatures) format via saga orchestration.

## Overview

The PDF Signing Service:

- ✅ **Consumes** signing commands from saga orchestrator
- ✅ **Signs** PDFs using PAdES-BASELINE-B format via deferred signing (CSC API v2.0 signHash)
- ✅ **Stores** signed PDFs to local filesystem or S3/MinIO (pluggable storage)
- ✅ **Publishes** saga replies to orchestrator (via outbox + Debezium CDC)
- ✅ **Publishes** notification events to notification-service (observer pattern)

## Architecture

### Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Java 21 |
| Framework | Spring Boot 3.4.13 |
| Messaging | Apache Camel 4.14.4 |
| HTTP Client | Spring Cloud OpenFeign |
| Circuit Breaker | Resilience4j |
| Database | PostgreSQL |
| Message Broker | Apache Kafka |
| PDF Processing | Apache PDFBox 3.0.6 |
| Cryptography | BouncyCastle 1.83 |
| Signing API | CSC API v2.0 (eidasremotesigning) |
| Saga Library | saga-commons 1.0.0-SNAPSHOT |
| Event Routing | Debezium CDC (outbox pattern) |

### Design Patterns

- **Saga Orchestration**: Command/reply pattern with saga orchestrator
- **Transactional Outbox**: Reliable event publishing via Debezium CDC
- **Dual-Publishing**: Saga replies (to orchestrator) + notifications (to observer)
- **Domain-Driven Design**: Aggregate roots, value objects, repository pattern
- **Deferred Signing**: PDF byte range digest computed locally, signature via CSC signHash

### Domain Model

**Aggregate Root:**
- `SignedPdfDocument` - Manages PDF signing lifecycle

**State Machine:**
```
PENDING → SIGNING → COMPLETED
               ↓
            FAILED
```

**Value Objects:**
- `SignedPdfDocumentId` - UUID wrapper
- `SigningStatus` - PENDING, SIGNING, COMPLETED, FAILED
- `PadesLevel` - BASELINE_B, BASELINE_T, BASELINE_LT, BASELINE_LTA

## Saga Orchestration Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                      Saga Orchestrator                          │
│                           │                                    │
│                           ▼                                    │
│              saga.command.pdf-signing                       │
│              (ProcessPdfSigningCommand)                   │
│                           │                                    │
│                           ▼                                    │
│              ┌──────────────────────────────┐               │
│              │  pdf-signing-service        │               │
│              │  (Spring Boot 3.4.13)       │               │
│              └──────────────────────────────┘               │
│                           │                                    │
│                           ├── [Deferred Signing Flow]            │
│                           │  1. Download PDF                   │
│                           │  2. Compute byte range digest       │
│                           │  3. CSC authorize → SAD            │
│                           │  4. CSC signHash (digest only)     │
│                           │  5. Build CMS/PKCS#7 (BouncyCastle) │
│                           │  6. Embed signature (PDFBox)       │
│                           │                                    │
│                           ▼                                    │
│              ┌───────────────────────────────┐              │
│              │  PostgreSQL (pdfsigning_db)   │              │
│              │  ┌─────────────────────────┐  │              │
│              │  │ signed_pdf_documents     │  │              │
│              │  │ outbox_events (CDC source)│  │              │
│              │  └─────────────────────────┘  │              │
│              └───────────────────────────────┘              │
│                           │                                    │
│            ┌──────────────┴───────────────┐                  │
│            │                            │                  │
│            ▼                            ▼                  │
│   saga.reply.pdf-signing      notification.events               │
│    (via Debezium CDC)          (via Debezium CDC)             │
│            │                            │                  │
│            ▼                            ▼                  │
│   ┌─────────────────────┐     ┌──────────────────────┐         │
│   │ Saga Orchestrator    │     │ notification-service │         │
│   │ (next saga step)     │     │ (email/webhook)       │         │
│   └─────────────────────┘     └──────────────────────┘         │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

## Kafka Topics (Saga Orchestration)

### Consumed Topics

| Topic | Event | Purpose |
|-------|-------|---------|
| `saga.command.pdf-signing` | `ProcessPdfSigningCommand` | Sign PDF command from orchestrator |
| `saga.compensation.pdf-signing` | `CompensatePdfSigningCommand` | Rollback command from orchestrator |

### Published Topics (via outbox + Debezium CDC)

| Topic | Event | Purpose |
|-------|-------|---------|
| `saga.reply.pdf-signing` | `PdfSigningReplyEvent` | Reply to orchestrator (SUCCESS/FAILURE/COMPENSATED) |
| `notification.events` | `PdfSignedNotificationEvent` / `PdfSigningFailedNotificationEvent` | Notification to notification-service (observer) |
| `document.archive` | `DocumentArchiveEvent` | Fire-and-forget archival (SIGNED_PDF stored by document-storage-service) |
| `pdf.signing.dlq` | Failed events | Dead Letter Queue |

### Event Schemas

**ProcessPdfSigningCommand** (input):
```json
{
  "sagaId": "saga-uuid",
  "sagaStep": "sign-pdf",
  "correlationId": "correlation-uuid",
  "documentId": "invoice-uuid",
  "invoiceNumber": "INV-2024-001",
  "documentType": "INVOICE",
  "pdfUrl": "http://pdf-generation-service/...",
  "pdfSize": 45678,
  "xmlEmbedded": true
}
```

**PdfSigningReplyEvent** (output to orchestrator):
```json
{
  "sagaId": "saga-uuid",
  "sagaStep": "sign-pdf",
  "correlationId": "correlation-uuid",
  "status": "SUCCESS",
  "signedDocumentId": "document-uuid",
  "signedPdfUrl": "http://localhost:8089/signed/...",
  "signedPdfSize": 48234,
  "transactionId": "TXN-uuid",
  "certificate": "-----BEGIN CERTIFICATE-----...",
  "signatureLevel": "PAdES-BASELINE-B",
  "signatureTimestamp": "2025-01-29T10:31:00Z"
}
```

**PdfSignedNotificationEvent** (output to notification-service):
```json
{
  "invoiceId": "invoice-uuid",
  "invoiceNumber": "INV-2024-001",
  "documentType": "INVOICE",
  "signedDocumentId": "document-uuid",
  "signedPdfUrl": "http://localhost:8089/signed/...",
  "signedPdfSize": 48234,
  "signatureLevel": "PAdES-BASELINE-B",
  "signatureTimestamp": "2025-01-29T10:31:00Z",
  "correlationId": "correlation-uuid"
}
```

## Database Schema

### signed_pdf_documents Table

- `id` (UUID) - Primary key
- `invoice_id` (VARCHAR) - Reference to invoice (unique constraint for idempotency)
- `invoice_number` (VARCHAR) - Invoice identifier
- `document_type` (VARCHAR) - Document type (INVOICE, TAX_INVOICE, etc.)
- `original_pdf_url` (VARCHAR) - URL of unsigned PDF
- `original_pdf_size` (BIGINT) - Original PDF file size
- `signed_pdf_path` (VARCHAR) - Filesystem path to signed PDF
- `signed_pdf_url` (VARCHAR) - Public URL to access signed PDF
- `signed_pdf_size` (BIGINT) - Signed PDF file size
- `transaction_id` (VARCHAR) - CSC API transaction ID (SAD token)
- `certificate` (TEXT) - PEM-encoded signing certificate
- `signature_level` (VARCHAR) - Signature level (PAdES-BASELINE-B)
- `signature_timestamp` (TIMESTAMP) - Signature timestamp
- `status` (VARCHAR) - PENDING, SIGNING, COMPLETED, FAILED
- `error_message` (TEXT) - Error message if failed
- `retry_count` (INTEGER) - Number of retry attempts
- `correlation_id` (VARCHAR) - Correlation ID for tracing
- Timestamps: `created_at`, `completed_at`, `updated_at`

### outbox_events Table (Transactional Outbox Pattern)

- `id` (UUID) - Primary key
- `aggregate_type` (VARCHAR) - Aggregate type (e.g., "SignedPdfDocument")
- `aggregate_id` (VARCHAR) - Aggregate ID
- `event_type` (VARCHAR) - Event type (e.g., "PdfSigningReply")
- `payload` (TEXT) - Event payload (JSON)
- `status` (VARCHAR) - PENDING, PUBLISHED, FAILED
- `topic` (VARCHAR) - Target Kafka topic for Debezium CDC routing
- `partition_key` (VARCHAR) - Kafka partition key
- `headers` (TEXT) - Kafka headers (JSON)
- `created_at`, `published_at` (TIMESTAMP) - Timestamps

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_NAME` | Database name | `pdfsigning_db` |
| `KAFKA_BROKERS` | Kafka bootstrap servers | `localhost:9092` |
| `CSC_SERVICE_URL` | eidasremotesigning URL | `http://localhost:9000` |
| `CSC_CREDENTIAL_ID` | CSC credential ID | `default-credential` |
| `CSC_CLIENT_ID` | CSC client ID | `pdf-signing-service` |
| `CSC_HASH_ALGO` | Hash algorithm (SHA256, SHA384, SHA512) | `SHA256` |
| `PADES_LEVEL` | PAdES conformance level | `BASELINE_B` |
| `STORAGE_PROVIDER` | Storage backend (`local` or `s3`) | `local` |
| `SIGNED_PDF_STORAGE_PATH` | Local storage path for signed PDFs | `/var/signed-documents` |
| `SIGNED_PDF_STORAGE_BASE_URL` | Base URL for signed PDF access (local) | `http://localhost:8089` |
| `S3_BUCKET_NAME` | S3/MinIO bucket name | `etax-signed-pdfs` |
| `AWS_REGION` | AWS region | `us-east-1` |
| `AWS_ACCESS_KEY` | S3/MinIO access key | `minioadmin` |
| `AWS_SECRET_KEY` | S3/MinIO secret key | `minioadmin` |
| `S3_ENDPOINT` | Custom S3/MinIO endpoint | `http://localhost:9100` |
| `S3_PATH_STYLE_ACCESS` | Force path-style S3 access | `true` |
| `S3_BASE_URL` | Public S3 base URL | `http://localhost:9100/etax-signed-pdfs` |
| `SIGNING_MAX_RETRIES` | Maximum retry attempts (0-10) | `3` |
| `OUTBOX_CLEANUP_ENABLED` | Enable outbox cleanup job | `false` |

### Application Properties

The service uses typed `@ConfigurationProperties` classes with JSR-303 validation:

**CscProperties** (`app.csc.*`):
```yaml
app:
  csc:
    client-id: pdf-signing-service      # OAuth2 client ID
    credential-id: default-credential   # Required: Signing credential ID
    hash-algo: SHA256                   # SHA256, SHA384, or SHA512
    cert-validation:
      enabled: true                     # Enable certificate validation
      max-validity-days: 365            # Max cert validity (1-3650 days)
      min-validity-remaining-days: 7    # Min remaining validity (0-365 days)
    sad-token:
      min-expiry-seconds: 60            # Min SAD token expiry (1-3600s)
      max-expiry-seconds: 3600          # Max SAD token expiry (60-86400s)
```

**KafkaProperties** (`app.kafka.*`):
```yaml
app:
  kafka:
    bootstrap-servers: localhost:9092   # Required: host:port,host:port
    topics:
      saga-command: saga.command.pdf-signing        # Required
      saga-compensation: saga.compensation.pdf-signing  # Required
      saga-reply: saga.reply.pdf-signing            # Required
      notification-events: notification.events       # Required
      dlq: pdf.signing.dlq                         # Required
```

**StorageProperties** (`app.storage.*`):
```yaml
app:
  storage:
    provider: local                     # "local" or "s3"
    local:
      base-path: /var/signed-documents  # Required for local provider
      base-url: http://localhost:8089    # Required: HTTP/HTTPS URL
    s3:
      bucket-name: etax-signed-pdfs      # Required for S3 provider
      region: us-east-1                  # Required for S3 provider
      access-key: minioadmin             # Optional (use IAM roles in prod)
      secret-key: minioadmin             # Optional (use IAM roles in prod)
      endpoint: http://localhost:9100    # Optional: HTTP/HTTPS URL
      path-style-access: true            # Default: false (use true for MinIO)
      base-url: http://localhost:9100/etax-signed-pdfs  # Optional: HTTP/HTTPS URL
```

**SigningProperties** (`app.signing.*`):
```yaml
app:
  signing:
    max-retries: 3                      # Max retry attempts (0-10)
```

## Storage Providers

The service supports pluggable storage backends via the `STORAGE_PROVIDER` environment variable.

### Local Storage (Default)

Stores signed PDFs on the local filesystem with date-based directory structure:

```
{SIGNED_PDF_STORAGE_PATH}/YYYY/MM/DD/signed-pdf-{documentId}.pdf
```

**Configuration:**
```bash
export STORAGE_PROVIDER=local
export SIGNED_PDF_STORAGE_PATH=/var/signed-documents
export SIGNED_PDF_STORAGE_BASE_URL=http://localhost:8089
```

**Public URL format:** `http://localhost:8089/signed-documents/YYYY/MM/DD/signed-pdf-{documentId}.pdf`

### S3/MinIO Storage

Stores signed PDFs in S3 or MinIO with date-based key structure:

```
signed-pdfs/YYYY/MM/DD/signed-pdf-{documentId}.pdf
```

**Configuration:**
```bash
export STORAGE_PROVIDER=s3
export S3_BUCKET_NAME=etax-signed-pdfs
export AWS_REGION=us-east-1
export AWS_ACCESS_KEY=minioadmin
export AWS_SECRET_KEY=minioadmin
export S3_ENDPOINT=http://localhost:9100
export S3_PATH_STYLE_ACCESS=true
export S3_BASE_URL=http://localhost:9100/etax-signed-pdfs
```

**Public URL format:** `http://localhost:9100/etax-signed-pdfs/signed-pdfs/YYYY/MM/DD/signed-pdf-{documentId}.pdf`

> **Note:** The `S3_ENDPOINT` and `S3_PATH_STYLE_ACCESS` settings are typically used for MinIO or S3-compatible services. For AWS S3, these can be omitted.

## Running the Service

### Prerequisites

1. **PostgreSQL** database with `pdfsigning_db`
2. **Kafka** broker
3. **eidasremotesigning service** (CSC API v2.0) on `localhost:9000`
4. **saga-commons** library installed: `cd /home/wpanther/projects/etax/saga-commons && mvn clean install`
5. **Debezium CDC** connector registered (for outbox event routing)
6. **Storage backend**:
   - **Local**: Directory with write permissions (e.g., `/var/signed-documents`)
   - **S3/MinIO**: S3 bucket or MinIO instance accessible from the service

### Build

```bash
mvn clean package
```

### Run Locally

**With local storage (default):**
```bash
export DB_HOST=localhost
export KAFKA_BROKERS=localhost:9092
export CSC_SERVICE_URL=http://localhost:9000
export STORAGE_PROVIDER=local
export SIGNED_PDF_STORAGE_PATH=/var/signed-documents
export SIGNED_PDF_STORAGE_BASE_URL=http://localhost:8089

mkdir -p /var/signed-documents

mvn spring-boot:run
```

**With S3/MinIO storage:**
```bash
export DB_HOST=localhost
export KAFKA_BROKERS=localhost:9092
export CSC_SERVICE_URL=http://localhost:9000
export STORAGE_PROVIDER=s3
export S3_BUCKET_NAME=etax-signed-pdfs
export AWS_REGION=us-east-1
export AWS_ACCESS_KEY=minioadmin
export AWS_SECRET_KEY=minioadmin
export S3_ENDPOINT=http://localhost:9100
export S3_PATH_STYLE_ACCESS=true
export S3_BASE_URL=http://localhost:9100/etax-signed-pdfs

mvn spring-boot:run
```

### Run Tests

```bash
# Run all tests (188 unit tests)
mvn test

# Run with coverage (JaCoCo 90% requirement)
mvn verify
```

### Database Migrations

```bash
mvn flyway:migrate
mvn flyway:info
```

## Debezium CDC Setup

The service uses the **transactional outbox pattern** with Debezium CDC for reliable event publishing.

**Register the connector:**
```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @debezium/connector-config.json
```

**Check status:**
```bash
curl http://localhost:8083/connectors/pdf-signing-outbox-connector/status
```

See `debezium/DEBEZIUM_SETUP.md` for complete Debezium setup documentation.

## Project Structure

```
src/main/java/com/wpanther/pdfsigning/
├── PdfSigningServiceApplication.java
├── domain/
│   ├── model/              # SignedPdfDocument aggregate, PadesLevel enum
│   ├── repository/         # Repository interfaces
│   ├── service/            # DomainPdfSigningService, SigningPort
│   └── event/              # Saga commands, replies, notifications
├── application/
│   └── service/            # SagaCommandHandler (process + compensate)
└── infrastructure/
    ├── persistence/        # JPA entities, repositories
    │   └── outbox/         # Outbox pattern (CDC source)
    ├── adapter/
    │   ├── primary/        # Kafka consumers, REST endpoints
    │   │   └── kafka/      # SagaCommandKafkaAdapter
    │   └── secondary/      # External service integrations
    │       ├── csc/        # CscSigningAdapter (CSC API)
    │       ├── download/   # HttpDocumentDownloadAdapter
    │       ├── pdf/        # PadesSignatureAdapter
    │       └── storage/    # LocalStorageAdapter, S3StorageAdapter
    ├── client/             # CSC API Feign clients
    │   └── csc/            # CSC API DTOs, validators
    ├── messaging/          # Event publishers (outbox)
    ├── pdf/                # PadesSignatureEmbedder, CertificateParser
    └── config/
        ├── properties/     # @ConfigurationProperties classes
        │   ├── CscProperties.java
        │   ├── KafkaProperties.java
        │   ├── StorageProperties.java
        │   └── SigningProperties.java
        └── SagaRouteConfig.java
```

### Architecture Patterns

- **Hexagonal Architecture (Ports and Adapters)**: Clean separation between domain and infrastructure
- **Domain-Driven Design**: Aggregate roots, value objects, repository pattern
- **Saga Orchestration**: Command/reply pattern with saga orchestrator
- **Transactional Outbox**: Reliable event publishing via Debezium CDC
- **Dual-Publishing**: Saga replies (to orchestrator) + notifications (to observer)
- **Deferred Signing**: PDF byte range digest computed locally, signature via CSC signHash

### Code Quality & Security

The codebase maintains high standards through:
- **Thread Safety**: Proper `@PostConstruct` initialization with `synchronized` methods and `volatile` fields
- **Charset Encoding**: Explicit UTF-8 encoding for all string operations (prevents platform-default issues)
- **Input Validation**: PDF header validation (5-byte magic number check), file size limits
- **Exception Handling**: Specific exception catching (domain exceptions vs unexpected errors)
- **Cross-Platform**: Windows-compatible path handling using `Path.relativize()` and `java.io.File.separator`
- **Test Coverage**: 188 unit tests with JaCoCo 90% line coverage requirement per package
- **Documentation**: Comprehensive Javadoc on all public APIs

## Key Features

### Hexagonal Architecture
- Clean separation between domain logic and infrastructure
- Ports define contracts (SigningPort, DocumentStoragePort)
- Adapters implement ports for external systems
- Easy to test and swap implementations

### Pluggable Storage Backends
- Local filesystem storage (default) with date-based directory structure
- AWS S3 or MinIO storage support
- Runtime selection via `STORAGE_PROVIDER` environment variable
- Consistent `DocumentStoragePort` interface across providers

### Typed Configuration Properties
- `@ConfigurationProperties` classes group related configuration
- JSR-303 validation annotations provide early feedback
- Type-safe configuration with IDE auto-completion
- Organized by domain (CSC, Kafka, Storage, Signing)

### Saga Orchestration
- Command/reply pattern with saga orchestrator
- Compensation support for rollback scenarios
- Idempotent command processing

### Transactional Outbox
- Events saved in same transaction as business state
- Debezium CDC streams to Kafka reliably
- Exactly-once delivery semantics

### Dual-Publishing
- Saga replies → orchestrator (for coordination)
- Notification events → notification-service (observer pattern)

### Idempotency
- Unique constraint on `invoice_id` prevents duplicate signing
- Already completed documents trigger immediate SUCCESS reply

### Retry Logic
- Failed signings retried up to configurable max attempts (default: 3)
- Configurable via `app.signing.max-retries` property

### Circuit Breaker
- Resilience4j circuit breaker protects CSC API calls
- Automatic fallback and recovery

### Apache Camel Error Handling
- Dead Letter Channel with exponential backoff
- Failed events routed to DLQ topic

## Recent Changes

### Hexagonal Architecture Migration (2025)

Complete migration to hexagonal architecture with improved code quality:

**Architecture:**
- Migrated from layered to hexagonal architecture (ports and adapters pattern)
- Domain layer now framework-independent with no Spring dependencies
- Application layer orchestrates use cases via domain services
- Infrastructure layer contains all external system adapters

**Code Quality Improvements:**
- Fixed thread safety issues in S3StorageAdapter (@PostConstruct + volatile)
- Added explicit UTF-8 charset encoding to prevent platform-specific issues
- Implemented proper PDF header validation (5-byte magic number check)
- Improved Windows path handling with Path.relativize()
- Added specific exception catching (domain vs unexpected exceptions)
- Enhanced certificate encoding error handling
- Removed unused imports and improved Javadoc coverage

**Test Results:**
- All 188 unit tests passing
- JaCoCo coverage: 90% line coverage requirement per package
- BUILD SUCCESS verified

## CSC API Integration (Deferred Signing)

The service uses **deferred signing** via the CSC `signHash` endpoint:

1. **Download PDF** from PDF generation service
2. **Compute byte range digest** locally (Apache PDFBox 3.0.6)
3. **Authorize** with CSC API → SAD token
4. **Sign hash** via `POST /csc/v2/signatures/signHash` → raw signature
5. **Build CMS/PKCS#7** signature locally (BouncyCastle 1.83)
6. **Embed signature** into PDF locally (Apache PDFBox 3.0.6)

**PAdES Signed Attributes** (per ETSI EN 319 142-1):
- `contentType` (id-data)
- `messageDigest` (SHA-256 of byte range)
- `signingTime` (timestamp)
- `signingCertificateV2` (certificate hash)

**SubFilter:** `ETSI.CAdES.detached`

## PAdES Conformance Levels

| Level | Description | TSA | OCSP/CRL |
|-------|-------------|-----|----------|
| BASELINE_B | Basic signature | No | No |
| BASELINE_T | With timestamp | Yes | No |
| BASELINE_LT | Long-term validation | Yes | Yes |
| BASELINE_LTA | Archive timestamp | Yes | Yes + Archive |

**Current:** `BASELINE_B` (minimum for Revenue Department compliance)

Future enhancements can add TSA/OCSP support for higher PAdES levels.

## Actuator Endpoints

- `/actuator/health` - Health check
- `/actuator/health/camel` - Camel health status
- `/actuator/camelroutes` - List all Camel routes
- `/actuator/metrics` - Application metrics

## Documentation

- **SAGA_MIGRATION_PLAN.md** - Detailed saga migration plan
- **debezium/DEBEZIUM_SETUP.md** - Debezium CDC configuration guide

## License

MIT License

## Contact

Maintained by wpanther (rabbit_roger@yahoo.com)
