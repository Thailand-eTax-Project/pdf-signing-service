# PDF Signing Service — Integration Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a full end-to-end integration test suite to `pdf-signing-service` that calls the real eidasremotesigning CSC API, stores signed PDFs in MinIO, and verifies outbox events — mirroring the xml-signing-service integration test structure.

**Architecture:** An `AbstractFullIntegrationTest` base class bootstraps eidasremotesigning (OAuth2 + BCFKS credential), pre-uploads PDF fixtures to MinIO, and provides Kafka/DB/MinIO helpers. `SagaCommandFullIntegrationTest` extends it with 13 test scenarios across 6 nested classes. All tests require running Docker containers started via `./scripts/test-containers-start.sh --with-eidas --with-debezium --auto-deploy-connectors`.

**Tech Stack:** JUnit 5, Spring Boot Test, Apache Kafka (KafkaConsumer/KafkaTemplate), AWS SDK S3 v2 (MinIO), Awaitility, Feign (RequestInterceptor), PostgreSQL JDBC, AssertJ

---

## File Map

| Action | File |
|---|---|
| Modify | `pom.xml` |
| Create | `src/test/resources/samples/tax-invoice-pdfa3.pdf` |
| Create | `src/test/resources/samples/invoice-pdfa3.pdf` |
| Create | `src/test/java/com/wpanther/pdfsigning/integration/support/EidasRemoteSigningTestHelper.java` |
| Create | `src/test/java/com/wpanther/pdfsigning/integration/config/TestKafkaProducerConfig.java` |
| Create | `src/test/java/com/wpanther/pdfsigning/integration/config/FullIntegrationTestConfiguration.java` |
| Create | `src/test/resources/application-full-integration-test.yml` |
| Create | `src/test/java/com/wpanther/pdfsigning/integration/AbstractFullIntegrationTest.java` |
| Create | `src/test/java/com/wpanther/pdfsigning/integration/SagaCommandFullIntegrationTest.java` |

---

## Task 1: Add test dependencies and integration Maven profile to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add awaitility and spring-kafka test dependencies**

In `pom.xml`, add these two dependencies after the last `<dependency>` block (before `</dependencies>` at line 273):

```xml
        <!-- Awaitility for async polling in integration tests -->
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <version>4.2.0</version>
            <scope>test</scope>
        </dependency>

        <!-- Spring Kafka (for KafkaTemplate/KafkaConsumer in integration tests) -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Add maven-surefire-plugin to exclude integration tests from default run**

In `pom.xml`, add this plugin inside `<build><plugins>` (after the `flyway-maven-plugin` block, before the `jacoco-maven-plugin` block):

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <excludes>
                        <exclude>**/integration/**</exclude>
                    </excludes>
                </configuration>
            </plugin>
```

- [ ] **Step 3: Add the integration Maven profile**

In `pom.xml`, add this block after `</build>` and before `</project>`:

```xml
    <profiles>
        <profile>
            <id>integration</id>
            <properties>
                <integration.tests.enabled>true</integration.tests.enabled>
            </properties>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                            <excludes>
                                <exclude>none</exclude>
                            </excludes>
                            <systemPropertyVariables>
                                <integration.tests.enabled>true</integration.tests.enabled>
                            </systemPropertyVariables>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```

- [ ] **Step 4: Verify compilation**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/pdf-signing-service
mvn compile -q
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "test(pdf-signing): add integration test dependencies and Maven profile"
```

---

## Task 2: Copy PDF fixture files into test resources

**Files:**
- Create: `src/test/resources/samples/tax-invoice-pdfa3.pdf`
- Create: `src/test/resources/samples/invoice-pdfa3.pdf`

- [ ] **Step 1: Create the samples directory and copy both PDFs**

```bash
mkdir -p src/test/resources/samples
cp /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service/target/preview/taxinvoice-pdfa3-preview.pdf \
   src/test/resources/samples/tax-invoice-pdfa3.pdf
cp /home/wpanther/projects/etax/invoice-microservices/services/invoice-pdf-generation-service/target/preview/invoice-pdfa3-preview.pdf \
   src/test/resources/samples/invoice-pdfa3.pdf
```

- [ ] **Step 2: Verify both files are valid PDFs**

```bash
head -c 5 src/test/resources/samples/tax-invoice-pdfa3.pdf | cat -v
head -c 5 src/test/resources/samples/invoice-pdfa3.pdf | cat -v
```

Expected output for each: `%PDF-` (the 5-byte PDF header).

- [ ] **Step 3: Commit**

```bash
git add src/test/resources/samples/
git commit -m "test(pdf-signing): add PDF/A-3 fixture files for integration tests"
```

---

## Task 3: Create EidasRemoteSigningTestHelper

**Files:**
- Create: `src/test/java/com/wpanther/pdfsigning/integration/support/EidasRemoteSigningTestHelper.java`

- [ ] **Step 1: Create the support package and helper class**

Create `src/test/java/com/wpanther/pdfsigning/integration/support/EidasRemoteSigningTestHelper.java`:

```java
package com.wpanther.pdfsigning.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Bootstraps eidasremotesigning for full integration tests.
 *
 * <p>On first call to {@link #setupOnce}, this helper:
 * <ol>
 *   <li>Registers an OAuth2 client at {@code POST /client-registration}</li>
 *   <li>Obtains a Bearer token via {@code POST /oauth2/token} (client_credentials grant)</li>
 *   <li>Inserts a BCFKS signing credential directly into the eidasremotesigning DB.</li>
 * </ol>
 *
 * <p>Required containers (start with --with-eidas flag):
 * <pre>
 *   ./scripts/test-containers-start.sh --with-eidas --with-debezium --auto-deploy-connectors
 * </pre>
 */
public final class EidasRemoteSigningTestHelper {

    private static final Logger log = LoggerFactory.getLogger(EidasRemoteSigningTestHelper.class);

    private static final String BCFKS_KEYSTORE_PATH = "/app/keystores/eidas-signing.bfks";
    private static final String BCFKS_KEYSTORE_PASSWORD = "eidas-signing-2024";
    private static final String BCFKS_CERTIFICATE_ALIAS = "signing-key";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile SetupResult cachedResult;
    private static volatile String cachedClientId;
    private static volatile String cachedClientSecret;
    private static volatile String cachedEidasBaseUrl;
    private static volatile String cachedToken;
    private static volatile Instant tokenExpiry = Instant.EPOCH;

    private EidasRemoteSigningTestHelper() {}

    public record SetupResult(String clientId, String credentialId, String accessToken) {}

    public static synchronized SetupResult setupOnce(
            String eidasBaseUrl,
            String pgJdbcUrl,
            String pgUser,
            String pgPassword) {

        if (cachedResult != null) {
            return cachedResult;
        }

        log.info("[EidasTestHelper] Setting up eidasremotesigning at {}", eidasBaseUrl);
        cachedEidasBaseUrl = eidasBaseUrl;

        try {
            ClientCredentials creds = registerClient(eidasBaseUrl);
            cachedClientId = creds.clientId();
            cachedClientSecret = creds.clientSecret();
            log.info("[EidasTestHelper] Registered OAuth2 client: {}", creds.clientId());

            String token = fetchNewToken(eidasBaseUrl, creds.clientId(), creds.clientSecret());
            log.info("[EidasTestHelper] Obtained Bearer token (expires at {})", tokenExpiry);

            String credId = insertBcfksCredential(pgJdbcUrl, pgUser, pgPassword, creds.clientId());
            log.info("[EidasTestHelper] Inserted BCFKS credential {} for client {}", credId, creds.clientId());

            cachedResult = new SetupResult(creds.clientId(), credId, token);
            return cachedResult;

        } catch (Exception e) {
            throw new IllegalStateException(
                    "[EidasTestHelper] Failed to set up eidasremotesigning. " +
                    "Ensure containers are running: " +
                    "./scripts/test-containers-start.sh --with-eidas --with-debezium --auto-deploy-connectors",
                    e
            );
        }
    }

    public static String getValidToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }
        if (cachedClientId == null || cachedEidasBaseUrl == null) {
            throw new IllegalStateException("[EidasTestHelper] Not initialized. Call setupOnce first.");
        }
        log.info("[EidasTestHelper] Refreshing OAuth2 token");
        return fetchNewToken(cachedEidasBaseUrl, cachedClientId, cachedClientSecret);
    }

    private static ClientCredentials registerClient(String eidasBaseUrl) throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "clientName", "pdf-signing-service-full-integration-test",
                "scopes", java.util.Set.of("signing"),
                "grantTypes", java.util.Set.of("client_credentials")
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(eidasBaseUrl + "/client-registration"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IllegalStateException(
                    "Client registration failed — HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = MAPPER.readTree(response.body());
        return new ClientCredentials(
                json.get("clientId").asText(),
                json.get("clientSecret").asText()
        );
    }

    private static String fetchNewToken(String eidasBaseUrl, String clientId, String clientSecret) {
        try {
            String credentials = clientId + ":" + clientSecret;
            String basicAuth = "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(eidasBaseUrl + "/oauth2/token"))
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=signing"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", basicAuth)
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "OAuth2 token request failed — HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            String token = json.get("access_token").asText();
            long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong(3600) : 3600;
            tokenExpiry = Instant.now().plusSeconds(expiresIn - 60);
            cachedToken = token;
            return token;

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch OAuth2 token from eidasremotesigning", e);
        }
    }

    private static String insertBcfksCredential(
            String pgJdbcUrl, String pgUser, String pgPassword, String clientId) throws Exception {

        String credId = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO signing_certificates
                    (id, storage_type, certificate_alias, keystore_path,
                     keystore_password, client_id, active, created_at, description)
                VALUES
                    (?, 'BCFKS', ?, ?, ?, ?, true, CURRENT_TIMESTAMP,
                     'Integration test credential — inserted by EidasRemoteSigningTestHelper')
                """;

        try (Connection conn = DriverManager.getConnection(pgJdbcUrl, pgUser, pgPassword);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, credId);
            stmt.setString(2, BCFKS_CERTIFICATE_ALIAS);
            stmt.setString(3, BCFKS_KEYSTORE_PATH);
            stmt.setString(4, BCFKS_KEYSTORE_PASSWORD);
            stmt.setString(5, clientId);
            int rows = stmt.executeUpdate();
            if (rows != 1) {
                throw new IllegalStateException("Expected 1 row inserted for signing_certificate, got: " + rows);
            }
        }
        return credId;
    }

    private record ClientCredentials(String clientId, String clientSecret) {}
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn test-compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wpanther/pdfsigning/integration/support/EidasRemoteSigningTestHelper.java
git commit -m "test(pdf-signing): add EidasRemoteSigningTestHelper for integration test bootstrap"
```

---

## Task 4: Create TestKafkaProducerConfig and FullIntegrationTestConfiguration

**Files:**
- Create: `src/test/java/com/wpanther/pdfsigning/integration/config/TestKafkaProducerConfig.java`
- Create: `src/test/java/com/wpanther/pdfsigning/integration/config/FullIntegrationTestConfiguration.java`

- [ ] **Step 1: Create TestKafkaProducerConfig**

Create `src/test/java/com/wpanther/pdfsigning/integration/config/TestKafkaProducerConfig.java`:

```java
package com.wpanther.pdfsigning.integration.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Profile("full-integration-test")
public class TestKafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> testProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> testKafkaTemplate() {
        return new KafkaTemplate<>(testProducerFactory());
    }
}
```

- [ ] **Step 2: Create FullIntegrationTestConfiguration**

Create `src/test/java/com/wpanther/pdfsigning/integration/config/FullIntegrationTestConfiguration.java`:

```java
package com.wpanther.pdfsigning.integration.config;

import com.wpanther.pdfsigning.integration.support.EidasRemoteSigningTestHelper;
import feign.RequestInterceptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Spring test configuration for full integration tests.
 *
 * <p>Provides four beans absent from the production application context:
 * <ol>
 *   <li><strong>cscBearerTokenInterceptor</strong> — Feign interceptor that adds
 *       {@code Authorization: Bearer <token>} to every CSC API call.</li>
 *   <li><strong>fullIntegrationJdbcTemplate</strong> — {@link JdbcTemplate} for test DB assertions.</li>
 *   <li><strong>minioVerificationS3Client</strong> — {@link S3Client} pointing at MinIO on localhost:9100.</li>
 *   <li><strong>minioS3Presigner</strong> — {@link S3Presigner} for generating presigned URLs for test PDF upload.</li>
 * </ol>
 */
@TestConfiguration
@Import(TestKafkaProducerConfig.class)
public class FullIntegrationTestConfiguration {

    @Bean
    public RequestInterceptor cscBearerTokenInterceptor() {
        return requestTemplate -> {
            String token = EidasRemoteSigningTestHelper.getValidToken();
            if (token != null && !token.isBlank()) {
                requestTemplate.header("Authorization", "Bearer " + token);
            }
        };
    }

    @Bean("fullIntegrationJdbcTemplate")
    public JdbcTemplate fullIntegrationJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("minioVerificationS3Client")
    public S3Client minioVerificationS3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .endpointOverride(URI.create("http://localhost:9100"))
                .forcePathStyle(true)
                .build();
    }

    @Bean("minioS3Presigner")
    public S3Presigner minioS3Presigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .endpointOverride(URI.create("http://localhost:9100"))
                .build();
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn test-compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/wpanther/pdfsigning/integration/config/
git commit -m "test(pdf-signing): add integration test Kafka, Feign, and MinIO configuration beans"
```

---

## Task 5: Create application-full-integration-test.yml

**Files:**
- Create: `src/test/resources/application-full-integration-test.yml`

- [ ] **Step 1: Create the YAML file**

Create `src/test/resources/application-full-integration-test.yml`:

```yaml
server:
  port: 0

spring:
  application:
    name: pdf-signing-service-full-integration-test

  main:
    allow-bean-definition-overriding: true

  datasource:
    url: jdbc:postgresql://localhost:5433/pdfsigning_db
    driver-class-name: org.postgresql.Driver
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    hikari:
      maximum-pool-size: 5
      connection-timeout: 10000

  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate
    show-sql: false

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9093}
    consumer:
      group-id: pdf-signing-full-integration-test
      auto-offset-reset: earliest
      enable-auto-commit: false

camel:
  springboot:
    name: pdf-signing-camel-full-integration-test
    main-run-controller: false
  dataformat:
    jackson:
      auto-discover-object-mapper: true

eureka:
  client:
    enabled: false

app:
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9093}
    topics:
      saga-command: saga.command.pdf-signing
      saga-compensation: saga.compensation.pdf-signing
      saga-reply: saga.reply.pdf-signing
      notification-events: notification.events
      dlq: pdf.signing.dlq

  csc:
    service-url: ${CSC_SERVICE_URL:http://localhost:9000}
    auth-endpoint: /csc/v2/oauth2/authorize
    sign-hash-endpoint: /csc/v2/signatures/signHash
    # client-id and credential-id are injected at runtime by @DynamicPropertySource
    credential-id: ${CSC_CREDENTIAL_ID:dynamic}
    client-id: ${CSC_CLIENT_ID:dynamic}
    hash-algo: SHA256
    timeout-seconds: 60
    sad:
      min-expiry-seconds: 60
      max-expiry-seconds: 3600
    cert:
      validation:
        enabled: true
      max-validity-days: 365
      min-validity-remaining-days: 7

  pades:
    level: BASELINE_B

  storage:
    provider: s3
    s3:
      bucket-name: etax-signed-pdfs
      region: us-east-1
      access-key: minioadmin
      secret-key: minioadmin
      endpoint: http://localhost:9100
      path-style-access: true
      presigned-url-ttl-minutes: 60

  pdf:
    max-size-bytes: 10485760

saga:
  outbox:
    cleanup:
      enabled: false
    publisher:
      batch-size: 100
      poll-interval-millis: 1000
      max-retries: 3

resilience4j:
  circuitbreaker:
    instances:
      csc-auth:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
      csc-sign-hash:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
  timelimiter:
    instances:
      csc-auth:
        timeoutDuration: 60s
      csc-sign-hash:
        timeoutDuration: 60s

management:
  health:
    circuitbreakers:
      enabled: false
  endpoints:
    enabled-by-default: false
    web:
      exposure:
        include: health

logging:
  level:
    root: WARN
    com.wpanther.pdfsigning: TRACE
    org.apache.camel: INFO
    org.apache.camel.component.kafka: DEBUG
    feign: DEBUG
```

- [ ] **Step 2: Commit**

```bash
git add src/test/resources/application-full-integration-test.yml
git commit -m "test(pdf-signing): add application-full-integration-test.yml with S3/MinIO storage config"
```

---

## Task 6: Create AbstractFullIntegrationTest

**Files:**
- Create: `src/test/java/com/wpanther/pdfsigning/integration/AbstractFullIntegrationTest.java`

- [ ] **Step 1: Create the abstract base class**

Create `src/test/java/com/wpanther/pdfsigning/integration/AbstractFullIntegrationTest.java`:

```java
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
import java.util.HashMap;
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
        int bucketIndex = pathPart.indexOf("/" + MINIO_BUCKET_NAME + "/");
        if (bucketIndex < 0) {
            throw new IllegalArgumentException(
                    "URL does not contain bucket '" + MINIO_BUCKET_NAME + "': " + presignedUrl);
        }
        return pathPart.substring(bucketIndex + MINIO_BUCKET_NAME.length() + 2);
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
```

- [ ] **Step 2: Verify compilation**

```bash
mvn test-compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wpanther/pdfsigning/integration/AbstractFullIntegrationTest.java
git commit -m "test(pdf-signing): add AbstractFullIntegrationTest base class with eIDAS, MinIO, Kafka helpers"
```

---

## Task 7: Create SagaCommandFullIntegrationTest

**Files:**
- Create: `src/test/java/com/wpanther/pdfsigning/integration/SagaCommandFullIntegrationTest.java`

- [ ] **Step 1: Create the test class**

Create `src/test/java/com/wpanther/pdfsigning/integration/SagaCommandFullIntegrationTest.java`:

```java
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
```

- [ ] **Step 2: Verify compilation**

```bash
mvn test-compile -q
```

Expected: `BUILD SUCCESS` with no errors. All imports resolve: `SagaStep.SIGN_PDF`, `ProcessPdfSigningCommand`, `CompensatePdfSigningCommand`, `JsonNode`, `assertThat`, `await`.

- [ ] **Step 3: Run unit tests to confirm nothing broke**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS`. The 23 existing unit tests pass. Integration tests are excluded (no `**/integration/**` runs without `-Pintegration`).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/wpanther/pdfsigning/integration/SagaCommandFullIntegrationTest.java
git commit -m "test(pdf-signing): add SagaCommandFullIntegrationTest with 13 end-to-end scenarios"
```

---

## Task 8: Run integration tests against live containers

This task requires the Docker test environment to be running.

- [ ] **Step 1: Start required containers**

```bash
cd /home/wpanther/projects/etax/invoice-microservices
./scripts/test-containers-start.sh --with-eidas --with-debezium --auto-deploy-connectors
```

Wait until all containers are healthy (Kafka, PostgreSQL, MinIO, eidasremotesigning, Debezium).

- [ ] **Step 2: Verify eidasremotesigning is reachable**

```bash
curl -s http://localhost:9000/csc/v2/info | python3 -m json.tool
```

Expected: JSON response with CSC API info (no connection refused error).

- [ ] **Step 3: Verify MinIO is reachable**

```bash
curl -s http://localhost:9100/minio/health/live
```

Expected: HTTP 200.

- [ ] **Step 4: Run all integration tests**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/pdf-signing-service
mvn test -Pintegration -Dtest="SagaCommandFullIntegrationTest" -Dlogging.level.com.wpanther.pdfsigning=DEBUG
```

Expected: All 13 tests pass. Example output:
```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 5: If a test fails, diagnose using the log output**

Common failures and fixes:
- `ConditionEvaluationResult disabled` → integration.tests.enabled not set; confirm `-Pintegration` flag
- `EidasTestHelper setup failed` → eidasremotesigning not started; check `./scripts/test-containers-start.sh --with-eidas`
- `NoSuchKeyException` in compensation test → Debezium CDC not routing outbox events; check Debezium connector status at `http://localhost:8083/connectors`
- `ConditionTimeoutException` → signing took >60s; increase `awaitDocumentStatus` timeout or check eidasremotesigning logs
- `BucketAlreadyExistsException` with non-409 status → unexpected error; check MinIO logs

- [ ] **Step 6: Commit final state**

```bash
git add -p  # review any fixups
git commit -m "test(pdf-signing): integration tests passing against live containers"
```

---

## Self-Review Notes

**Spec coverage check:**
- ✅ Section 4 (file structure) — all 9 files covered across Tasks 1–7
- ✅ Section 5 (EidasRemoteSigningTestHelper) — Task 3, clientName changed to `pdf-signing-service-full-integration-test`
- ✅ Section 6 (FullIntegrationTestConfiguration) — Task 4, includes `minioS3Presigner` bean
- ✅ Section 7 (TestKafkaProducerConfig) — Task 4, profile `full-integration-test`
- ✅ Section 8 (AbstractFullIntegrationTest) — Task 6, all helpers including `uploadTestPdfs()`, `objectExistsInMinIO()`, `downloadFromMinIO()`, `extractKeyFromPresignedUrl()`
- ✅ Section 9 (test scenarios) — Task 7, all 13 tests across 6 nested classes
- ✅ Section 10 (application-full-integration-test.yml) — Task 5
- ✅ Section 11 (key differences) — reflected in test code (no document type detection, `/ByteRange` check, `notification.events` topic, `signedDocumentId` aggregate)
- ✅ Section 12 (known limitation) — tests use `signed_pdf_url` from DB row, not hardcoded key patterns; limitation does not affect correctness
