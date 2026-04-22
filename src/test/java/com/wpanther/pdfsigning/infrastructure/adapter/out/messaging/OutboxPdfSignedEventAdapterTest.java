package com.wpanther.pdfsigning.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.pdfsigning.infrastructure.config.properties.KafkaProperties;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPdfSignedEventAdapter Tests")
class OutboxPdfSignedEventAdapterTest {

    @Mock private OutboxService outboxService;
    @Mock private KafkaProperties kafkaProperties;
    private OutboxPdfSignedEventAdapter adapter;

    @BeforeEach
    void setUp() {
        KafkaProperties.Topics topics = new KafkaProperties.Topics();
        topics.setNotificationEvents("pdf.signed");
        when(kafkaProperties.getTopics()).thenReturn(topics);
        adapter = new OutboxPdfSignedEventAdapter(outboxService, new ObjectMapper(), kafkaProperties);
    }

    @Test
    @DisplayName("publishPdfSignedNotification routes to pdf.signed topic")
    void publishPdfSignedNotification_routesToNotificationTopic() {
        adapter.publishPdfSignedNotification(
            "saga-1", "inv-1", "INV-001", "TAX_INVOICE",
            "doc-1", "http://example.com/signed.pdf", 12345L,
            "PAdES-BASELINE-B", Instant.now(), "corr-1"
        );

        verify(outboxService).saveWithRouting(
            any(), eq("SignedPdfDocument"), any(),
            eq("pdf.signed"), eq("inv-1"), any()
        );
    }

    @Test
    @DisplayName("publishPdfSigningFailureNotification routes to pdf.signed topic")
    void publishPdfSigningFailureNotification_routesToNotificationTopic() {
        adapter.publishPdfSigningFailureNotification(
            "saga-1", "inv-1", "INV-001", "TAX_INVOICE",
            "Signing failed", "corr-1"
        );

        verify(outboxService).saveWithRouting(
            any(), eq("SignedPdfDocument"), any(),
            eq("pdf.signed"), eq("inv-1"), any()
        );
    }
}
