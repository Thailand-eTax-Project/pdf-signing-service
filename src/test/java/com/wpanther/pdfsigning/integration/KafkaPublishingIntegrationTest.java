package com.wpanther.pdfsigning.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies that outbox events are published to Kafka topics via Debezium CDC.
 *
 * <p>This test validates the full outbox → CDC → Kafka pipeline:
 * <ol>
 *   <li>Send saga command on {@code saga.command.pdf-signing}</li>
 *   <li>Service processes command, writes outbox rows to PostgreSQL</li>
 *   <li>Debezium CDC captures outbox table changes and publishes to Kafka</li>
 *   <li>Test consumer verifies messages arrive on {@code saga.reply.pdf-signing}
 *       and {@code notification.events}</li>
 * </ol>
 *
 * <p>This is the "last mile" test — outbox DB verification alone (SagaCommandFullIntegrationTest)
 * does not prove Debezium is routing events to the correct Kafka topics.</p>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>PostgreSQL on localhost:5433 (pdfsigning_db)</li>
 *   <li>Kafka on localhost:9093</li>
 *   <li>eidasremotesigning on localhost:9000</li>
 *   <li>MinIO on localhost:9100 (bucket {@code etax-signed-pdfs})</li>
 *   <li>Debezium connector configured to capture {@code outbox_events} table</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   mvn test -Pintegration -Dtest="KafkaPublishingIntegrationTest" -Dintegration.tests.enabled=true
 * </pre>
 */
@DisplayName("Full Integration: outbox → Debezium CDC → Kafka topic delivery")
@Tag("full-integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class KafkaPublishingIntegrationTest extends AbstractFullIntegrationTest {

    private static final String COMMAND_TOPIC = "saga.command.pdf-signing";
    private static final String SAGA_REPLY_TOPIC = "saga.reply.pdf-signing";
    private static final String NOTIFICATION_TOPIC = "notification.events";
    private static final Duration KAFKA_POLL_TIMEOUT = Duration.ofSeconds(2);

    // =========================================================================
    // Saga reply on Kafka
    // =========================================================================

    @Nested
    @DisplayName("saga.reply.pdf-signing Kafka delivery")
    class SagaReplyKafkaDelivery {

        @Test
        @DisplayName("SUCCESS saga reply should arrive on saga.reply.pdf-signing Kafka topic")
        void shouldPublishSuccessReplyToKafka() throws Exception {
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();
            String sagaId = sagaIdFor(correlationId);

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, "TINV-KAFKA-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            // 1. Wait for DB completion (proves service processed the command)
            awaitDocumentStatus(documentId, "COMPLETED");

            // 2. Wait for outbox row in DB (proves outbox was written)
            awaitOutboxEventCount(sagaId, 1);

            // 3. Poll Kafka for the CDC-published saga reply
            JsonNode reply = awaitKafkaMessage(SAGA_REPLY_TOPIC, sagaId);

            assertThat(reply.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(reply.get("sagaId").asText()).isEqualTo(sagaId);
            assertThat(reply.get("correlationId").asText()).isEqualTo(correlationId);
            assertThat(reply.has("signedPdfUrl")).isTrue();
            assertThat(reply.get("signedPdfUrl").asText()).isNotBlank();
        }

        @Test
        @DisplayName("Saga reply payload should contain all required signing metadata")
        void sagaReplyPayloadShouldContainAllMetadata() throws Exception {
            String documentId = newDocumentId();
            String documentNumber = "TINV-META-001";
            String correlationId = newCorrelationId();
            String sagaId = sagaIdFor(correlationId);

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, documentNumber, "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            awaitDocumentStatus(documentId, "COMPLETED");
            awaitOutboxEventCount(sagaId, 1);

            JsonNode reply = awaitKafkaMessage(SAGA_REPLY_TOPIC, sagaId);

            assertThat(reply.has("signedPdfUrl")).isTrue();
            assertThat(reply.has("signedPdfSize")).isTrue();
            assertThat(reply.has("transactionId")).isTrue();
            assertThat(reply.has("certificate")).isTrue();
            assertThat(reply.has("signatureLevel")).isTrue();
            assertThat(reply.has("signatureTimestamp")).isTrue();

            assertThat(reply.get("signedPdfSize").asLong()).isGreaterThan(0L);
            assertThat(reply.get("certificate").asText()).startsWith("-----BEGIN CERTIFICATE-----");
        }

        @Test
        @DisplayName("COMPENSATED saga reply should arrive on Kafka after compensation")
        void shouldPublishCompensatedReplyToKafka() throws Exception {
            String documentId = newDocumentId();
            String processCorrelationId = newCorrelationId();

            // Sign first
            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, "TINV-KCOMP-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, processCorrelationId));
            awaitDocumentStatus(documentId, "COMPLETED");

            // Compensate
            String compensateCorrelationId = newCorrelationId();
            String compensateSagaId = sagaIdFor(compensateCorrelationId);

            sendEvent("saga.compensation.pdf-signing", documentId,
                    createCompensateCommand(documentId, compensateCorrelationId));

            awaitDocumentDeleted(documentId);
            awaitOutboxEventCount(compensateSagaId, 1);

            JsonNode reply = awaitKafkaMessage(SAGA_REPLY_TOPIC, compensateSagaId);

            assertThat(reply.get("status").asText()).isEqualTo("COMPENSATED");
            assertThat(reply.get("sagaId").asText()).isEqualTo(compensateSagaId);
        }
    }

    // =========================================================================
    // Notification events on Kafka
    // =========================================================================

    @Nested
    @DisplayName("notification.events Kafka delivery")
    class NotificationKafkaDelivery {

        @Test
        @DisplayName("PdfSignedNotification should arrive on notification.events Kafka topic")
        void shouldPublishPdfSignedNotificationToKafka() throws Exception {
            String documentId = newDocumentId();
            String documentNumber = "TINV-NTFK-001";
            String correlationId = newCorrelationId();

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, documentNumber, "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            // Wait for DB completion and outbox
            awaitDocumentStatus(documentId, "COMPLETED");
            String signedDocumentId = (String) getDocumentByDocumentId(documentId).get("id");
            awaitOutboxEventCount(signedDocumentId, 1);

            // Poll Kafka for the notification event
            JsonNode notification = awaitKafkaMessage(NOTIFICATION_TOPIC, signedDocumentId);

            assertThat(notification.has("eventType")).isTrue();
            assertThat(notification.has("documentId")).isTrue();
            assertThat(notification.get("documentId").asText()).isEqualTo(documentId);
            assertThat(notification.get("documentType").asText()).isEqualTo("TAX_INVOICE");
            assertThat(notification.has("signedPdfUrl")).isTrue();
        }
    }

    // =========================================================================
    // Dual-publish atomicity on Kafka
    // =========================================================================

    @Nested
    @DisplayName("Dual-publish atomicity")
    class DualPublishAtomicity {

        @Test
        @DisplayName("Both saga reply and notification should arrive on Kafka for a single signing")
        void shouldPublishBothEventsToKafkaAtomically() throws Exception {
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();
            String sagaId = sagaIdFor(correlationId);

            sendEvent(COMMAND_TOPIC, documentId,
                    createProcessCommand(documentId, "TINV-DUAL-001", "TAX_INVOICE",
                            taxInvoicePdfUrl, taxInvoicePdfSize, correlationId));

            awaitDocumentStatus(documentId, "COMPLETED");

            String signedDocumentId = (String) getDocumentByDocumentId(documentId).get("id");

            // Both outbox rows must exist in DB
            awaitOutboxEventCount(sagaId, 1);
            awaitOutboxEventCount(signedDocumentId, 1);

            // Both must arrive on Kafka
            JsonNode sagaReply = awaitKafkaMessage(SAGA_REPLY_TOPIC, sagaId);
            assertThat(sagaReply.get("status").asText()).isEqualTo("SUCCESS");

            JsonNode notification = awaitKafkaMessage(NOTIFICATION_TOPIC, signedDocumentId);
            assertThat(notification.has("documentId")).isTrue();
        }
    }

    // =========================================================================
    // Kafka helpers
    // =========================================================================

    /**
     * Polls a Kafka topic until a message containing the expected aggregateId
     * in its value is found, or the Awaitility timeout expires.
     *
     * <p>Debezium CDC wraps outbox payloads in an envelope: {@code {"payload": {...}, "headers": {...}}}.
     * This method extracts the inner {@code payload} field before returning.</p>
     */
    private JsonNode awaitKafkaMessage(String topic, String expectedAggregateId) {
        // Seek to beginning so we catch events published before the consumer was assigned
        sagaReplyKafkaConsumer.subscribe(List.of(topic));
        sagaReplyKafkaConsumer.poll(Duration.ofMillis(500)); // trigger assignment
        sagaReplyKafkaConsumer.seekToBeginning(sagaReplyKafkaConsumer.assignment());

        AtomicReference<JsonNode> found = new AtomicReference<>();
        StringBuilder lastValue = new StringBuilder();

        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .alias("waiting for message on " + topic + " containing " + expectedAggregateId)
                .until(() -> {
                    var records = sagaReplyKafkaConsumer.poll(KAFKA_POLL_TIMEOUT);
                    for (ConsumerRecord<String, String> record : records) {
                        lastValue.append(record.value());
                        if (record.value().contains(expectedAggregateId)) {
                            try {
                                JsonNode root = objectMapper.readTree(record.value());
                                // Debezium outbox SMT with expand.json.payload=false sends
                                // the payload as a JSON string — parse it again
                                JsonNode payload;
                                if (root.isTextual()) {
                                    payload = objectMapper.readTree(root.asText());
                                } else if (root.has("payload")) {
                                    JsonNode raw = root.get("payload");
                                    payload = raw.isTextual()
                                            ? objectMapper.readTree(raw.asText())
                                            : raw;
                                } else {
                                    payload = root;
                                }
                                found.set(payload);
                                return true;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to parse Kafka message JSON", e);
                            }
                        }
                    }
                    return false;
                });

        JsonNode result = found.get();
        if (result == null) {
            throw new AssertionError("No message containing '" + expectedAggregateId
                    + "' found on topic '" + topic + "' within timeout. Last value seen: "
                    + lastValue);
        }
        return result;
    }
}
