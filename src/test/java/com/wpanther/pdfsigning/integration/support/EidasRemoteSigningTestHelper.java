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
