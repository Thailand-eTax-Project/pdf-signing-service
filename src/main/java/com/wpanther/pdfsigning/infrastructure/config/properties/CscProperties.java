package com.wpanther.pdfsigning.infrastructure.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for CSC (Cloud Signature Consortium) API integration.
 * <p>
 * Groups all CSC-related configuration including authentication,
 * credential settings, hash algorithm, and SAD token validation.
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.csc")
public class CscProperties {

    /**
     * OAuth2 client ID for CSC API authentication.
     * May be null if the CSC service doesn't require explicit client ID.
     */
    private String clientId;

    /**
     * Credential ID to use for signing operations.
     * This identifies which key/certificate to use in the CSC service.
     */
    @NotBlank(message = "CSC credential ID must not be blank")
    private String credentialId;

    /**
     * Hash algorithm to use for signature operations.
     * Default: SHA-256 (format required by eidasremotesigning CSC API)
     */
    @Pattern(regexp = "^(SHA-256|SHA-384|SHA-512)$", message = "Hash algorithm must be SHA-256, SHA-384, or SHA-512")
    private String hashAlgo = "SHA-256";

    /**
     * PIN for unlocking the BCFKS/PKCS#11 keystore.
     * Required for BCFKS and PKCS#11 storage types. Empty for AWS KMS.
     */
    private String pin = "";

    /**
     * Certificate validation settings.
     */
    private final CertValidation certValidation = new CertValidation();

    /**
     * SAD (Signature Authorization Token) validation settings.
     */
    private final SadToken sadToken = new SadToken();

    /**
     * Certificate validation configuration.
     */
    @Data
    public static class CertValidation {

        /**
         * Whether certificate validation is enabled.
         * Default: true
         */
        private boolean enabled = true;

        /**
         * Maximum validity period for certificates in days.
         * Certificates valid beyond this period will be rejected.
         * Default: 365 days (1 year)
         */
        @Min(value = 1, message = "Max validity days must be at least 1")
        @Max(value = 3650, message = "Max validity days must not exceed 3650 (10 years)")
        private int maxValidityDays = 365;

        /**
         * Minimum remaining validity period in days.
         * Certificates expiring sooner than this will trigger a warning.
         * Default: 7 days
         */
        @Min(value = 0, message = "Min validity remaining days must be non-negative")
        @Max(value = 365, message = "Min validity remaining days must not exceed 365")
        private int minValidityRemainingDays = 7;
    }

    /**
     * SAD token validation configuration.
     */
    @Data
    public static class SadToken {

        /**
         * Minimum SAD token expiry time in seconds.
         * Tokens expiring sooner than this will be rejected.
         * Default: 60 seconds
         */
        @Min(value = 1, message = "Min expiry seconds must be at least 1")
        @Max(value = 3600, message = "Min expiry seconds must not exceed 3600 (1 hour)")
        private int minExpirySeconds = 60;

        /**
         * Maximum SAD token expiry time in seconds.
         * Tokens expiring later than this will be rejected.
         * Default: 3600 seconds (1 hour)
         */
        @Min(value = 60, message = "Max expiry seconds must be at least 60")
        @Max(value = 86400, message = "Max expiry seconds must not exceed 86400 (24 hours)")
        private int maxExpirySeconds = 3600;

        /**
         * Clock skew tolerance in seconds. Accounts for local clock being behind
         * the CSC service clock. If remaining validity is less than or equal to
         * this tolerance, the token is considered expired to avoid signHash
         * failing with "token expired" (CSC would reject an expired token anyway).
         * Default: 60 seconds
         */
        @Min(value = 0, message = "Clock skew tolerance must be non-negative")
        @Max(value = 300, message = "Clock skew tolerance must not exceed 300 (5 minutes)")
        private int clockSkewToleranceSeconds = 60;
    }
}
