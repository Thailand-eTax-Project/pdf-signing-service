package com.wpanther.pdfsigning.infrastructure.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Kafka messaging.
 * <p>
 * Groups Kafka-related configuration including bootstrap servers
 * and topic names for saga commands, replies, notifications, and DLQ.
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaProperties {

    /**
     * Kafka bootstrap servers (comma-separated list of host:port).
     * Default: localhost:9092
     */
    @NotBlank(message = "Kafka bootstrap servers must not be blank")
    @Pattern(regexp = "^([a-zA-Z0-9.-]+:\\d+,?)+$", message = "Kafka bootstrap servers must be in format 'host:port,host:port'")
    private String bootstrapServers = "localhost:9092";

    /**
     * Kafka consumer group ID for saga command consumer.
     * Separate from compensation consumer so command and compensation partitions
     * don't compete for the same consumer pool.
     */
    @NotBlank
    private String commandConsumerGroup = "pdf-signing-service-command";

    /**
     * Kafka consumer group ID for saga compensation consumer.
     * Separate from command consumer so compensation messages are processed
     * independently of command throughput.
     */
    @NotBlank
    private String compensationConsumerGroup = "pdf-signing-service-compensation";

    /**
     * Auto offset reset policy for Camel Kafka consumers.
     * Use "earliest" for production (process all unread messages) and
     * "latest" for integration tests (skip stale messages from previous runs).
     */
    private String autoOffsetReset = "earliest";

    /**
     * Topic configuration for various Kafka topics.
     */
    private final Topics topics = new Topics();

    /**
     * Kafka topic names configuration.
     */
    @Data
    public static class Topics {

        /**
         * Topic for consuming saga commands from orchestrator.
         * Default: saga.command.pdf-signing
         */
        @NotBlank(message = "Saga command topic must not be blank")
        private String sagaCommand = "saga.command.pdf-signing";

        /**
         * Topic for consuming saga compensation commands from orchestrator.
         * Default: saga.compensation.pdf-signing
         */
        @NotBlank(message = "Saga compensation topic must not be blank")
        private String sagaCompensation = "saga.compensation.pdf-signing";

        /**
         * Topic for publishing saga replies to orchestrator.
         * Default: saga.reply.pdf-signing
         */
        @NotBlank(message = "Saga reply topic must not be blank")
        private String sagaReply = "saga.reply.pdf-signing";

        /**
         * Topic for publishing notification events to notification-service.
         * Default: pdf.signed
         */
        @NotBlank(message = "Notification events topic must not be blank")
        private String notificationEvents = "pdf.signed";

        /**
         * Dead Letter Queue topic for failed messages.
         * Default: pdf.signing.dlq
         */
        @NotBlank(message = "DLQ topic must not be blank")
        private String dlq = "pdf.signing.dlq";
    }
}
