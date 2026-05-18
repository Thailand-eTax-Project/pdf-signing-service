package com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCAuthorizeResponse {

    @JsonProperty("sad")
    private String SAD;

    @JsonProperty("expiresIn")
    private Long expiresIn;
}
