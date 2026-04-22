package com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * CSC API v2.0 signHash request DTO.
 *
 * Used for deferred signing where only the hash digest is sent to CSC.
 * The raw signature is returned and locally embedded into the PDF.
 *
 * @see <a href="https://www.cloudsignatureconsortium.org/wp-content/uploads/2022/03/CSC-API-v2.0.0-2022-03.pdf">CSC API v2.0 Specification</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCSignatureRequest {

    /**
     * Client identifier for CSC authentication.
     */
    @JsonProperty("clientId")
    @NotBlank(message = "clientId is required")
    private String clientId;

    /**
     * Credential ID identifying which certificate to use for signing.
     */
    @JsonProperty("credentialID")
    @NotBlank(message = "credentialID is required")
    private String credentialID;

    /**
     * SAD (Signature Activation Data) token for authorization.
     * Obtained from CSC /credentials/authorize endpoint.
     */
    @JsonProperty("SAD")
    private String SAD;

    /**
     * Hash algorithm used for digest computation.
     * Supported values: SHA256, SHA384, SHA512
     */
    @JsonProperty("hashAlgo")
    @NotBlank(message = "hashAlgo is required")
    private String hashAlgo;

    /**
     * Data containing the hash(es) to sign.
     */
    @JsonProperty("signatureData")
    @NotNull(message = "signatureData is required")
    private SignatureData signatureData;

    /**
     * Optional: Enable asynchronous operation mode.
     * If true, returns operationID immediately instead of waiting for completion.
     * Not used in this implementation (synchronous signing).
     */
    @JsonProperty("async")
    private Boolean async;

    /**
     * Credentials for PIN-based key unlocking.
     * Required for BCFKS/PKCS#11 storage types alongside the SAD token.
     */
    @JsonProperty("credentials")
    private Credentials credentials;

    /**
     * Nested DTO for credentials (PIN-based key unlocking).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Credentials {

        @JsonProperty("pin")
        private Pin pin;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Pin {
            @JsonProperty("value")
            private String value;
        }
    }

    /**
     * Nested DTO for signature data.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SignatureData {

        /**
         * Array of base64url-encoded hash values to sign.
         * For PDF signing, this array contains exactly one hash (the PDF byte range digest).
         *
         * IMPORTANT: Use base64url encoding (URL-safe, no padding) not base64.
         */
        @JsonProperty("hashToSign")
        private String[] hashToSign;

        /**
         * Optional signature attributes.
         * For PAdES, most attributes are added locally in the CMS construction.
         * Empty placeholder for API compatibility.
         */
        @JsonProperty("signatureAttributes")
        private Object signatureAttributes;
    }
}
