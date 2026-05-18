package com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CSC DTO Tests")
class CSCDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("CSCAuthorizeRequest")
    class CSCAuthorizeRequestTests {

        @Test
        @DisplayName("Should serialize hashAlgorithmOID and hashes (not hashAlgo/hash)")
        void shouldSerializeToJson() throws Exception {
            CSCAuthorizeRequest request = CSCAuthorizeRequest.builder()
                .credentialID("credential-123")
                .numSignatures(1)
                .hashAlgorithmOID("2.16.840.1.101.3.4.2.1")
                .hashes(new String[]{"dGhpc2lzYXhash"})
                .description("Test authorization")
                .build();

            String json = objectMapper.writeValueAsString(request);

            assertThat(json).contains("\"credentialID\":\"credential-123\"");
            assertThat(json).contains("\"hashAlgorithmOID\":\"2.16.840.1.101.3.4.2.1\"");
            assertThat(json).contains("\"hashes\"");
            assertThat(json).doesNotContain("\"clientId\"");
            assertThat(json).doesNotContain("\"hashAlgo\"");
            assertThat(json).doesNotContain("\"hash\":");
        }

        @Test
        @DisplayName("numSignatures should serialize as JSON integer not string")
        void shouldSerializeNumSignaturesAsInteger() throws Exception {
            CSCAuthorizeRequest request = CSCAuthorizeRequest.builder()
                .credentialID("cred")
                .numSignatures(1)
                .build();

            String json = objectMapper.writeValueAsString(request);

            assertThat(json).contains("\"numSignatures\":1");
            assertThat(json).doesNotContain("\"numSignatures\":\"1\"");
        }

        @Test
        @DisplayName("authData should serialize as array of id/value objects")
        void shouldSerializeAuthData() throws Exception {
            CSCAuthorizeRequest request = CSCAuthorizeRequest.builder()
                .credentialID("cred")
                .authData(List.of(
                    CSCAuthorizeRequest.AuthDataEntry.builder().id("PIN").value("1234").build()
                ))
                .build();

            String json = objectMapper.writeValueAsString(request);

            assertThat(json).contains("\"authData\"");
            assertThat(json).contains("\"id\":\"PIN\"");
            assertThat(json).contains("\"value\":\"1234\"");
        }

        @Test
        @DisplayName("Should exclude null fields from JSON")
        void shouldExcludeNullFields() throws Exception {
            CSCAuthorizeRequest request = CSCAuthorizeRequest.builder()
                .credentialID("cred")
                .build();

            String json = objectMapper.writeValueAsString(request);

            assertThat(json).doesNotContain("numSignatures");
            assertThat(json).doesNotContain("hashAlgorithmOID");
            assertThat(json).doesNotContain("hashes");
            assertThat(json).doesNotContain("authData");
        }
    }

    @Nested
    @DisplayName("CSCAuthorizeResponse")
    class CSCAuthorizeResponseTests {

        @Test
        @DisplayName("Should serialize and deserialize SAD token")
        void shouldSerializeSADToken() throws Exception {
            CSCAuthorizeResponse response = new CSCAuthorizeResponse();
            response.setSAD("test-sad-token");
            response.setExpiresIn(300L);

            String json = objectMapper.writeValueAsString(response);
            CSCAuthorizeResponse deserialized = objectMapper.readValue(json, CSCAuthorizeResponse.class);

            assertThat(deserialized.getSAD()).isEqualTo("test-sad-token");
            assertThat(deserialized.getExpiresIn()).isEqualTo(300L);
        }

        @Test
        @DisplayName("Should not contain transactionID or authMode fields")
        void shouldNotContainRemovedFields() throws Exception {
            CSCAuthorizeResponse response = new CSCAuthorizeResponse();
            response.setSAD("token");

            String json = objectMapper.writeValueAsString(response);

            assertThat(json).doesNotContain("transactionID");
            assertThat(json).doesNotContain("authMode");
        }
    }

    @Nested
    @DisplayName("CSCSignatureRequest")
    class CSCSignatureRequestTests {

        @Test
        @DisplayName("Should serialize flat hashes array and hashAlgorithmOID at top level")
        void shouldSerializeFlatHashesAtTopLevel() throws Exception {
            CSCSignatureRequest request = CSCSignatureRequest.builder()
                .credentialID("cred")
                .SAD("sad-token")
                .hashAlgorithmOID("2.16.840.1.101.3.4.2.1")
                .hashes(new String[]{"base64hash"})
                .build();

            String json = objectMapper.writeValueAsString(request);

            assertThat(json).contains("\"hashes\"");
            assertThat(json).contains("\"hashAlgorithmOID\":\"2.16.840.1.101.3.4.2.1\"");
            assertThat(json).contains("base64hash");
            assertThat(json).doesNotContain("\"clientId\"");
            assertThat(json).doesNotContain("\"signatureData\"");
            assertThat(json).doesNotContain("\"credentials\"");
            assertThat(json).doesNotContain("\"hashAlgo\"");
        }

        @Test
        @DisplayName("Should deserialize with hashes and hashAlgorithmOID")
        void shouldDeserializeCorrectly() throws Exception {
            String json = "{\"credentialID\":\"cr\",\"SAD\":\"st\",\"hashAlgorithmOID\":\"2.16.840.1.101.3.4.2.1\",\"hashes\":[\"hash1\"]}";

            CSCSignatureRequest request = objectMapper.readValue(json, CSCSignatureRequest.class);

            assertThat(request.getCredentialID()).isEqualTo("cr");
            assertThat(request.getHashes()).containsExactly("hash1");
            assertThat(request.getHashAlgorithmOID()).isEqualTo("2.16.840.1.101.3.4.2.1");
        }
    }

    @Nested
    @DisplayName("CSCSignatureResponse")
    class CSCSignatureResponseTests {

        @Test
        @DisplayName("Should serialize and deserialize signature array")
        void shouldSerializeSignatureArray() throws Exception {
            CSCSignatureResponse response = CSCSignatureResponse.builder()
                .signatureAlgorithm("1.2.840.113549.1.1.11")
                .signatures(new String[]{"c2lnbmF0dXJlZmxvYg==", "c2lnMj"})
                .build();

            String json = objectMapper.writeValueAsString(response);
            CSCSignatureResponse deserialized = objectMapper.readValue(json, CSCSignatureResponse.class);

            assertThat(deserialized.getSignatures()).hasSize(2);
        }

        @Test
        @DisplayName("Should use responseID not operationID")
        void shouldUseResponseId() throws Exception {
            CSCSignatureResponse response = CSCSignatureResponse.builder()
                .signatures(new String[]{"sig"})
                .responseID("async-resp-123")
                .build();

            String json = objectMapper.writeValueAsString(response);

            assertThat(json).contains("\"responseID\":\"async-resp-123\"");
            assertThat(json).doesNotContain("operationID");
        }

        @Test
        @DisplayName("Should not contain certificate or timestampData fields")
        void shouldNotContainRemovedFields() throws Exception {
            CSCSignatureResponse response = CSCSignatureResponse.builder()
                .signatures(new String[]{"sig"})
                .build();

            String json = objectMapper.writeValueAsString(response);

            assertThat(json).doesNotContain("certificate");
            assertThat(json).doesNotContain("timestampData");
        }
    }
}
