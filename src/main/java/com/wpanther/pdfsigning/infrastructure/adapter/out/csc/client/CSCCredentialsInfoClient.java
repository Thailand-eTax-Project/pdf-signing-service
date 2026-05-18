package com.wpanther.pdfsigning.infrastructure.adapter.out.csc.client;

import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCCredentialsInfoRequest;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCCredentialsInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
    name = "csc-credentials-info-client",
    url = "${app.csc.service-url}"
)
public interface CSCCredentialsInfoClient {

    @PostMapping("${app.csc.credentials-info-endpoint}")
    CSCCredentialsInfoResponse getCredentialInfo(@RequestBody CSCCredentialsInfoRequest request);
}
