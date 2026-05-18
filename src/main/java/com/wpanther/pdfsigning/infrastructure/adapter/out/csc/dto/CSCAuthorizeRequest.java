package com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCAuthorizeRequest {

    @JsonProperty("credentialID")
    private String credentialID;

    @JsonProperty("numSignatures")
    private Integer numSignatures;

    @JsonProperty("hashAlgorithmOID")
    private String hashAlgorithmOID;

    @JsonProperty("hashes")
    private String[] hashes;

    @JsonProperty("authData")
    private List<AuthDataEntry> authData;

    @JsonProperty("description")
    private String description;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuthDataEntry {
        @JsonProperty("id")
        private String id;

        @JsonProperty("value")
        private String value;
    }
}
