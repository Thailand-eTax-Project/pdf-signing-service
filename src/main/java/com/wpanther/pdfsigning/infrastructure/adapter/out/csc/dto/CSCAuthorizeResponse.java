package com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CSC API authorize response DTO.
 *
 * Contains the SAD (Signature Activation Data) token used for signing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCAuthorizeResponse {

    /**
     * Signature Activation Data token.
     * eidasremotesigning returns "sad" (lowercase) per CSC API v2.0.
     */
    @JsonProperty("sad")
    private String SAD;

    /**
     * Expiration time of the SAD token (optional)
     */
    @JsonProperty("expiresIn")
    private Long expiresIn;
}
