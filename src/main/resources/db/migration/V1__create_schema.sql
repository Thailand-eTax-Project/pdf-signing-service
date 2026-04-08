-- PDF Signing Service schema (fresh installation)

-- ============================================================
-- signed_pdf_documents
-- ============================================================
CREATE TABLE signed_pdf_documents (
    id                  UUID            PRIMARY KEY,
    document_id         VARCHAR(100)    NOT NULL,
    document_number     VARCHAR(50)     NOT NULL,
    document_type       VARCHAR(50)     NOT NULL DEFAULT 'INVOICE',
    original_pdf_url    VARCHAR(500)    NOT NULL,
    original_pdf_size   BIGINT          NOT NULL,
    signed_pdf_path     VARCHAR(500),
    signed_pdf_url      VARCHAR(500),
    signed_pdf_size     BIGINT,
    transaction_id      VARCHAR(100),
    certificate         TEXT,
    signature_level     VARCHAR(50),
    signature_timestamp TIMESTAMPTZ,
    status              VARCHAR(20)     NOT NULL,
    error_message       TEXT,
    retry_count         INTEGER         NOT NULL DEFAULT 0,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id      VARCHAR(100)
);

CREATE UNIQUE INDEX idx_signed_pdf_document_id       ON signed_pdf_documents(document_id);
CREATE        INDEX idx_signed_pdf_status           ON signed_pdf_documents(status);
CREATE        INDEX idx_signed_pdf_document_number   ON signed_pdf_documents(document_number);
CREATE        INDEX idx_signed_pdf_document_type    ON signed_pdf_documents(document_type);
CREATE        INDEX idx_signed_pdf_created_at       ON signed_pdf_documents(created_at);

COMMENT ON TABLE  signed_pdf_documents                          IS 'Stores signed PDF document metadata and signing status';
COMMENT ON COLUMN signed_pdf_documents.id                       IS 'Primary key (UUID)';
COMMENT ON COLUMN signed_pdf_documents.document_id              IS 'Reference to document (unique for idempotency)';
COMMENT ON COLUMN signed_pdf_documents.document_number          IS 'Human-readable document identifier';
COMMENT ON COLUMN signed_pdf_documents.document_type            IS 'Document type: INVOICE, TAX_INVOICE, etc.';
COMMENT ON COLUMN signed_pdf_documents.original_pdf_url         IS 'URL of the unsigned PDF from pdf-generation-service';
COMMENT ON COLUMN signed_pdf_documents.signed_pdf_path          IS 'Filesystem path to the signed PDF';
COMMENT ON COLUMN signed_pdf_documents.signed_pdf_url           IS 'Public URL to access the signed PDF';
COMMENT ON COLUMN signed_pdf_documents.transaction_id           IS 'CSC API transaction ID';
COMMENT ON COLUMN signed_pdf_documents.certificate              IS 'PEM-encoded signing certificate';
COMMENT ON COLUMN signed_pdf_documents.signature_level          IS 'Signature level (e.g. PAdES-BASELINE-B)';
COMMENT ON COLUMN signed_pdf_documents.signature_timestamp      IS 'Timestamp from CSC signing service (UTC)';
COMMENT ON COLUMN signed_pdf_documents.status                   IS 'Signing status: PENDING, SIGNING, COMPLETED, FAILED';
COMMENT ON COLUMN signed_pdf_documents.retry_count              IS 'Number of retry attempts for failed signings';
COMMENT ON COLUMN signed_pdf_documents.correlation_id           IS 'Correlation ID for end-to-end request tracing';

-- ============================================================
-- outbox_events  (transactional outbox / Debezium CDC)
-- ============================================================
CREATE TABLE outbox_events (
    id              UUID            PRIMARY KEY,
    aggregate_type  VARCHAR(100)    NOT NULL,
    aggregate_id    VARCHAR(100)    NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    payload         TEXT            NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    retry_count     INTEGER         NOT NULL DEFAULT 0,
    error_message   TEXT,
    topic           VARCHAR(255),
    partition_key   VARCHAR(255),
    headers         TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status      ON outbox_events(status);
CREATE INDEX idx_outbox_created_at  ON outbox_events(created_at);
CREATE INDEX idx_outbox_aggregate   ON outbox_events(aggregate_id, aggregate_type);

COMMENT ON TABLE  outbox_events                IS 'Transactional outbox for reliable event publishing via Debezium CDC';
COMMENT ON COLUMN outbox_events.id             IS 'Unique event identifier';
COMMENT ON COLUMN outbox_events.aggregate_type IS 'Aggregate type (e.g. SignedPdfDocument)';
COMMENT ON COLUMN outbox_events.aggregate_id   IS 'Aggregate ID (e.g. document UUID)';
COMMENT ON COLUMN outbox_events.event_type     IS 'Event type (e.g. PdfSigningReply)';
COMMENT ON COLUMN outbox_events.payload        IS 'Event payload (JSON)';
COMMENT ON COLUMN outbox_events.status         IS 'PENDING, PUBLISHED, FAILED';
COMMENT ON COLUMN outbox_events.topic          IS 'Target Kafka topic for CDC routing';
COMMENT ON COLUMN outbox_events.partition_key  IS 'Kafka partition key';
COMMENT ON COLUMN outbox_events.headers        IS 'Message headers (JSON)';
COMMENT ON COLUMN outbox_events.created_at     IS 'Event creation timestamp (UTC)';
COMMENT ON COLUMN outbox_events.published_at   IS 'Event publication timestamp (UTC)';
