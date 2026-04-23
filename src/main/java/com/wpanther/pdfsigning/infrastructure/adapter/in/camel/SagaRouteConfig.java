package com.wpanther.pdfsigning.infrastructure.adapter.in.camel;

import com.wpanther.pdfsigning.application.usecase.SagaCommandPort;
import com.wpanther.pdfsigning.application.dto.event.CompensatePdfSigningCommand;
import com.wpanther.pdfsigning.application.dto.event.ProcessPdfSigningCommand;
import com.wpanther.pdfsigning.infrastructure.config.properties.KafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

/**
 * Apache Camel routes for saga command and compensation consumers.
 * Saga orchestration mode: consumes from orchestrator topics.
 *
 * Consumes from:
 * - saga.command.pdf-signing (from orchestrator - ProcessPdfSigningCommand)
 * - saga.compensation.pdf-signing (from orchestrator - CompensatePdfSigningCommand)
 *
 * Produces to:
 * - saga.reply.pdf-signing (via outbox + Debezium CDC to orchestrator)
 * - pdf.signed (via outbox + Debezium CDC to notification-service)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaRouteConfig extends RouteBuilder {

    private final SagaCommandPort sagaCommandPort;
    private final KafkaProperties kafkaProperties;

    @Override
    public void configure() throws Exception {
        String kafkaBrokers = kafkaProperties.getBootstrapServers();
        String sagaCommandTopic = kafkaProperties.getTopics().getSagaCommand();
        String sagaCompensationTopic = kafkaProperties.getTopics().getSagaCompensation();
        String dlqTopic = kafkaProperties.getTopics().getDlq();

        // Global error handler - Dead Letter Channel with retries
        errorHandler(deadLetterChannel("kafka:" + dlqTopic + "?brokers=" + kafkaBrokers)
                        .maximumRedeliveries(3)
                        .redeliveryDelay(1000)
                        .useExponentialBackOff()
                        .backOffMultiplier(2)
                        .maximumRedeliveryDelay(10000)
                        .logExhausted(true)
                        .logStackTrace(true));

        // ============================================================
        // CONSUMER ROUTE: saga.command.pdf-signing (from orchestrator)
        // ============================================================
        from("kafka:" + sagaCommandTopic
                        + "?brokers=" + kafkaBrokers
                        + "&groupId=" + kafkaProperties.getCommandConsumerGroup()
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError=true"
                        + "&maxPollRecords=100"
                        + "&consumersCount=3")
                        .routeId("saga-command-consumer")
                        .log("Received saga command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, ProcessPdfSigningCommand.class)
                        .process(exchange -> {
                                ProcessPdfSigningCommand cmd = exchange.getIn().getBody(ProcessPdfSigningCommand.class);
                                log.info("Processing saga command for saga: {}, document: {}",
                                                cmd.getSagaId(), cmd.getDocumentNumber());
                                sagaCommandPort.handleProcessPdfSigning(cmd);
                        })
                        .log("Successfully processed saga command for sagaId: ${body.sagaId}");

        // ============================================================
        // CONSUMER ROUTE: saga.compensation.pdf-signing (from orchestrator)
        // ============================================================
        from("kafka:" + sagaCompensationTopic
                        + "?brokers=" + kafkaBrokers
                        + "&groupId=" + kafkaProperties.getCompensationConsumerGroup()
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError=true"
                        + "&maxPollRecords=100"
                        + "&consumersCount=3")
                        .routeId("saga-compensation-consumer")
                        .log("Received compensation command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, CompensatePdfSigningCommand.class)
                        .process(exchange -> {
                                CompensatePdfSigningCommand cmd = exchange.getIn().getBody(CompensatePdfSigningCommand.class);
                                log.info("Processing compensation for saga: {}, document: {}",
                                                cmd.getSagaId(), cmd.getDocumentId());
                                sagaCommandPort.handleCompensatePdfSigning(cmd);
                        })
                        .log("Successfully processed compensation command for sagaId: ${body.sagaId}");
    }
}
