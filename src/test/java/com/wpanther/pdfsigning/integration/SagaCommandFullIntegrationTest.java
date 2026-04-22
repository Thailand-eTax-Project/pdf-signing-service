package com.wpanther.pdfsigning.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full end-to-end integration tests for the PDF signing saga command consumer.
 *
 * <p>These tests exercise the complete pipeline with no mocks:
 * <ul>
 *   <li>Kafka command → Apache Camel → SagaCommandHandler</li>
 *   <li>eidasremotesigning CSC API v2.0 signs the PDF (PAdES-BASELINE-B)</li>
 *   <li>Signed PDF stored in MinIO bucket {@code etax-signed-pdfs} on localhost:9100</li>
 *   <li>Outbox events written to PostgreSQL, published via Debezium CDC</li>
 * </ul>
 *
 * <p>Start required containers before running:
 * <pre>
 *   cd invoice-microservices
 *   ./scripts/test-containers-start.sh --with-eidas --with-debezium --auto-deploy-connectors
 * </pre>
 *
 * <p>Run:
 * <pre>
 *   cd services/pdf-signing-service
 *   mvn test -Pintegration -Dtest="SagaCommandFullIntegrationTest"
 * </pre>
 */
@DisplayName("Full Integration: saga.command.pdf-signing → CSC sign → MinIO store → outbox")
@Tag("full-integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class SagaCommandFullIntegrationTest extends AbstractFullIntegrationTest {

    private static final String COMMAND_TOPIC = "saga.command.pdf-signing";
    private static final String COMPENSATION_TOPIC = "saga.compensation.pdf-signing";

    // =========================================================================
    // Happy path
    // =========================================================================

    @Nested
    @DisplayName("Signing happy-path")
    class SigningHappyPath {

        @Test
        @DisplayName("Should sign TAX_INVOICE PDF end-to-end and reach COMPLETED status")
        void shouldSignTaxInvoicePdfEndToEnd() throws Exception {
            String documentId = newDocumentId();
            String documentNumber = "TINV-" + documentId.substring(4, 12);
            String correlationId = newCorrelationId();

            var command = createProcessCommand(documentId, documentNumber, "TAX_INVOICE",
                    taxInvoicePdfUrl, taxInvoicePdfSize, correlationId);
            sendEvent(COMMAND_TOPIC, documentId, command);

            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");

            assertThat(doc.get("document_id")).isEqualTo(documentId);
            assertThat(doc.get("document_number")).isEqualTo(documentNumber);
            assertThat(doc.get("document_type")).isEqualTo("TAX_INVOICE");
            assertThat(doc.get("status")).isEqualTo("COMPLETED");
            assertThat(doc.get("signed_pdf_url")).asString().isNotBlank();
            assertThat(doc.get("transaction_id")).asString().isNotBlank();
            assertThat(doc.get("certificate")).asString().startsWith("-----BEGIN CERTIFICATE-----");
            assertThat(doc.get("error_message")).isNull();
        }

        @Test
        @DisplayName("Should sign INVOICE PDF end-to-end and reach COMPLETED status")
        void shouldSignInvoicePdfEndToEnd() throws Exception {
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();

            var command = createProcessCommand(documentId, "INV-" + documentId.substring(4, 12),
                    "INVOICE", invoicePdfUrl, invoicePdfSize, correlationId);
            sendEvent(COMMAND_TOPIC, documentId, command);

            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");
            assertThat(doc.get("document_type")).isEqualTo("INVOICE");
            assertThat(doc.get("signed_pdf_url")).asString().isNotBlank();
            assertThat(doc.get("transaction_id")).asString().isNotBlank();
            assertThat(doc.get("error_message")).isNull();
        }
    }

    // =========================================================================
    // Signed PDF content verification
    // =========================================================================

    @Nested
    @DisplayName("Signed PDF content verification")
    class SignedPdfContentVerification {

        @Test
        @DisplayName("Signed PDF downloaded from MinIO should start with %%PDF- header")
        void signedPdfShouldBeValidPdf() throws Exception {
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, "TINV-PDFHDR-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");
            byte[] signedPdfBytes = downloadFromMinIO((String) doc.get("signed_pdf_url"));

            // %PDF- is bytes: 0x25 0x50 0x44 0x46 0x2D
            assertThat(signedPdfBytes.length).isGreaterThan(5);
            assertThat(signedPdfBytes[0]).isEqualTo((byte) 0x25);
            assertThat(signedPdfBytes[1]).isEqualTo((byte) 0x50);
            assertThat(signedPdfBytes[2]).isEqualTo((byte) 0x44);
            assertThat(signedPdfBytes[3]).isEqualTo((byte) 0x46);
            assertThat(signedPdfBytes[4]).isEqualTo((byte) 0x2D);
        }

        @Test
        @DisplayName("Signed PDF should contain /ByteRange — the PAdES signature marker")
        void signedPdfShouldContainPadesSignatureMarker() throws Exception {
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, "TINV-BYTERANGE-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");
            byte[] signedPdfBytes = downloadFromMinIO((String) doc.get("signed_pdf_url"));
            // PDF is binary; decode with ISO-8859-1 to avoid mangling non-UTF8 bytes
            String signedPdfText = new String(signedPdfBytes, StandardCharsets.ISO_8859_1);

            assertThat(signedPdfText)
                    .as("Signed PDF should contain /ByteRange — embedded by PDFBox for PAdES")
                    .contains("/ByteRange");
        }

        @Test
        @DisplayName("signed_pdf_size in DB should equal actual downloaded byte count")
        void signedPdfSizeInDbShouldMatchActualBytes() throws Exception {
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, "TINV-SIZE-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");
            long dbSize = (Long) doc.get("signed_pdf_size");
            byte[] signedPdfBytes = downloadFromMinIO((String) doc.get("signed_pdf_url"));

            assertThat(dbSize).isEqualTo(signedPdfBytes.length);
        }
    }

    // =========================================================================
    // Outbox event verification
    // =========================================================================

    @Nested
    @DisplayName("Outbox event correctness")
    class OutboxEventVerification {

        @Test
        @DisplayName("Should write notification.events outbox row with correct payload fields")
        void shouldWritePdfSignedNotificationWithCorrectFields() throws Exception {
            String documentId = newDocumentId();
            String documentNumber = "TINV-NTF-001";
            String correlationId = newCorrelationId();

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, documentNumber, "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");

            // notification.events aggregate_id = SignedPdfDocument UUID (id column)
            String signedDocumentId = doc.get("id").toString();
            awaitOutboxEventCount(signedDocumentId, 1);

            List<Map<String, Object>> notificationEvents = getOutboxEventsByAggregateId(signedDocumentId);
            assertThat(notificationEvents).hasSize(1);

            Map<String, Object> event = notificationEvents.get(0);
            assertThat(event.get("topic")).isEqualTo("notification.events");
            assertThat(event.get("aggregate_type")).isEqualTo("SignedPdfDocument");

            JsonNode payload = objectMapper.readTree((String) event.get("payload"));
            assertThat(payload.get("documentId").asText()).isEqualTo(documentId);
            assertThat(payload.get("documentType").asText()).isEqualTo("TAX_INVOICE");
            assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
            assertThat(payload.has("signedPdfUrl")).isTrue();
        }

        @Test
        @DisplayName("Should write saga.reply.pdf-signing outbox row with SUCCESS and signed PDF URL")
        void shouldWriteSagaReplySuccessWithSignedPdfUrl() throws Exception {
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();
            String sagaId = sagaIdFor(correlationId);

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, "TINV-REPLY-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            awaitDocumentStatus(documentId, "COMPLETED");
            awaitOutboxEventCount(sagaId, 1);

            List<Map<String, Object>> replyEvents = getOutboxEventsByAggregateId(sagaId);
            Map<String, Object> reply = replyEvents.stream()
                    .filter(e -> "saga.reply.pdf-signing".equals(e.get("topic")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No saga.reply.pdf-signing event found"));

            assertThat(reply.get("aggregate_type")).isEqualTo("SignedPdfDocument");
            assertThat(reply.get("aggregate_id")).isEqualTo(sagaId);
            assertThat(reply.get("partition_key")).isEqualTo(sagaId);

            String payloadStr = (String) reply.get("payload");
            assertThat(payloadStr).contains("SUCCESS");
            assertThat(payloadStr).contains(correlationId);

            JsonNode payloadNode = objectMapper.readTree(payloadStr);
            if (payloadNode.has("signedPdfUrl")) {
                assertThat(payloadNode.get("signedPdfUrl").asText()).isNotBlank();
            }
        }

        @Test
        @DisplayName("Should write both notification.events and saga.reply outbox rows atomically")
        void shouldWriteBothOutboxEventsAtomically() throws Exception {
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();
            String sagaId = sagaIdFor(correlationId);

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, "TINV-ATOMIC-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");
            String signedDocumentId = doc.get("id").toString();

            awaitOutboxEventCount(signedDocumentId, 1);
            awaitOutboxEventCount(sagaId, 1);

            List<Map<String, Object>> allEvents = getAllOutboxEvents();
            long notificationCount = allEvents.stream()
                    .filter(e -> "notification.events".equals(e.get("topic"))).count();
            long sagaReplyCount = allEvents.stream()
                    .filter(e -> "saga.reply.pdf-signing".equals(e.get("topic"))).count();

            assertThat(notificationCount).isEqualTo(1);
            assertThat(sagaReplyCount).isEqualTo(1);
        }
    }

    // =========================================================================
    // Idempotency
    // =========================================================================

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("Should not re-sign when duplicate command is received for a COMPLETED document")
        void shouldNotResignAlreadyCompletedDocument() throws Exception {
            String documentId = newDocumentId();
            String correlationId1 = newCorrelationId();

            var firstCommand = createProcessCommand(documentId, "TINV-IDEM-001", "TAX_INVOICE",
                    taxInvoicePdfUrl, taxInvoicePdfSize, correlationId1);
            sendEvent(COMMAND_TOPIC, documentId, firstCommand);
            awaitDocumentStatus(documentId, "COMPLETED");

            String firstSignedPdfUrl = (String) getDocumentByDocumentId(documentId).get("signed_pdf_url");

            // Duplicate with a different correlationId
            String correlationId2 = newCorrelationId();
            var duplicateCommand = createProcessCommand(documentId, "TINV-IDEM-001", "TAX_INVOICE",
                    taxInvoicePdfUrl, taxInvoicePdfSize, correlationId2);
            sendEvent(COMMAND_TOPIC, documentId, duplicateCommand);

            // Allow time for the duplicate to be processed
            Thread.sleep(5_000);

            // Still only 1 DB record
            Integer count = testJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM signed_pdf_documents WHERE document_id = ?",
                    Integer.class, documentId);
            assertThat(count).isEqualTo(1);

            // signed_pdf_url unchanged
            String currentSignedPdfUrl = (String) getDocumentByDocumentId(documentId).get("signed_pdf_url");
            assertThat(currentSignedPdfUrl).isEqualTo(firstSignedPdfUrl);

            // SUCCESS reply still written for the duplicate saga
            String sagaId2 = sagaIdFor(correlationId2);
            awaitOutboxEventCount(sagaId2, 1);
            List<Map<String, Object>> replyEvents = getOutboxEventsByAggregateId(sagaId2);
            assertThat(replyEvents).isNotEmpty();
            assertThat((String) replyEvents.get(0).get("payload")).contains("SUCCESS");
        }
    }

    // =========================================================================
    // Multiple documents
    // =========================================================================

    @Nested
    @DisplayName("Multiple documents in parallel")
    class MultipleDocuments {

        @Test
        @DisplayName("Should process TAX_INVOICE and INVOICE documents sent concurrently")
        void shouldProcessTwoDocumentsConcurrently() throws Exception {
            String docId1 = newDocumentId();
            String docId2 = newDocumentId();
            String corr1 = newCorrelationId();
            String corr2 = newCorrelationId();

            sendEvent(COMMAND_TOPIC, docId1,
                    createProcessCommand(docId1, "TINV-PAR-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, corr1));
            sendEvent(COMMAND_TOPIC, docId2,
                    createProcessCommand(docId2, "INV-PAR-001", "INVOICE",
                            invoicePdfUrl, invoicePdfSize, corr2));

            Map<String, Object> doc1 = awaitDocumentStatus(docId1, "COMPLETED");
            Map<String, Object> doc2 = awaitDocumentStatus(docId2, "COMPLETED");

            assertThat(doc1.get("document_type")).isEqualTo("TAX_INVOICE");
            assertThat(doc2.get("document_type")).isEqualTo("INVOICE");
            assertThat(doc1.get("signed_pdf_url")).asString().isNotBlank();
            assertThat(doc2.get("signed_pdf_url")).asString().isNotBlank();
        }
    }

    // =========================================================================
    // Compensation
    // =========================================================================

    @Nested
    @DisplayName("Compensation (saga rollback)")
    class Compensation {

        @Test
        @DisplayName("Should delete signed PDF from MinIO and DB on compensation")
        void shouldDeleteSignedPdfFromMinIOAndDbOnCompensation() throws Exception {
            String documentId = newDocumentId();
            String processCorrelationId = newCorrelationId();

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, "TINV-COMP-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, processCorrelationId));

            Map<String, Object> signedDoc = awaitDocumentStatus(documentId, "COMPLETED");
            String signedPdfUrl = (String) signedDoc.get("signed_pdf_url");

            assertThat(objectExistsInMinIO(signedPdfUrl)).isTrue();

            String compensateCorrelationId = newCorrelationId();
            sendEvent(COMPENSATION_TOPIC, documentId,
                    createCompensateCommand(documentId, compensateCorrelationId));

            awaitDocumentDeleted(documentId);

            String compensateSagaId = sagaIdFor(compensateCorrelationId);
            awaitOutboxEventCount(compensateSagaId, 1);

            List<Map<String, Object>> replyEvents = getOutboxEventsByAggregateId(compensateSagaId);
            Map<String, Object> reply = replyEvents.stream()
                    .filter(e -> "saga.reply.pdf-signing".equals(e.get("topic")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No saga.reply.pdf-signing event after compensation"));
            assertThat((String) reply.get("payload")).contains("COMPENSATED");

            await().atMost(15, TimeUnit.SECONDS)
                    .pollInterval(2, TimeUnit.SECONDS)
                    .until(() -> !objectExistsInMinIO(signedPdfUrl));
        }

        @Test
        @DisplayName("Should send COMPENSATED reply even when document was never signed (idempotent)")
        void shouldSendCompensatedReplyForNonExistentDocument() throws Exception {
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();
            String sagaId = sagaIdFor(correlationId);

            sendEvent(COMPENSATION_TOPIC, documentId,
                    createCompensateCommand(documentId, correlationId));

            awaitOutboxEventCount(sagaId, 1);
            List<Map<String, Object>> replyEvents = getOutboxEventsByAggregateId(sagaId);

            Map<String, Object> reply = replyEvents.stream()
                    .filter(e -> "saga.reply.pdf-signing".equals(e.get("topic")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No saga.reply.pdf-signing event"));
            assertThat((String) reply.get("payload")).contains("COMPENSATED");
        }
    }
}
