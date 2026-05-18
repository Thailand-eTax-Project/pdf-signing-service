package com.wpanther.pdfsigning.infrastructure.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.csc")
public class CscProperties {

    @NotBlank(message = "CSC credential ID must not be blank")
    private String credentialId;

    @Pattern(regexp = "^[0-9]+(\\.[0-9]+)+$", message = "hashAlgorithmOid must be a valid OID")
    private String hashAlgorithmOid = "2.16.840.1.101.3.4.2.1";

    private String pin = "";

    private final CertValidation certValidation = new CertValidation();

    private final SadToken sadToken = new SadToken();

    @Data
    public static class CertValidation {
        private boolean enabled = true;

        @Min(value = 1)  @Max(value = 3650)
        private int maxValidityDays = 365;

        @Min(value = 0)  @Max(value = 365)
        private int minValidityRemainingDays = 7;
    }

    @Data
    public static class SadToken {
        @Min(value = 1)  @Max(value = 3600)
        private int minExpirySeconds = 60;

        @Min(value = 60)  @Max(value = 86400)
        private int maxExpirySeconds = 3600;

        @Min(value = 0)  @Max(value = 300)
        private int clockSkewToleranceSeconds = 60;
    }
}
