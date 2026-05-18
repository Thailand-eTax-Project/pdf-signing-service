package com.wpanther.pdfsigning.infrastructure.adapter.out.csc;

import com.wpanther.pdfsigning.domain.model.SigningException;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.client.CSCCredentialsInfoClient;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCCredentialsInfoRequest;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCCredentialsInfoResponse;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.CertificateParser;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.CertificateValidator;
import com.wpanther.pdfsigning.infrastructure.config.properties.CscProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.cert.X509Certificate;

@Component
@RequiredArgsConstructor
@Slf4j
public class CredentialInfoCache {

    private final CSCCredentialsInfoClient credentialsInfoClient;
    private final CertificateParser certificateParser;
    private final CscProperties cscProperties;
    private final CertificateValidator certificateValidator;

    private volatile X509Certificate[] certChain;

    @PostConstruct
    public void init() {
        refresh();
    }

    public X509Certificate[] getCertChain() {
        if (certChain == null) {
            throw new IllegalStateException("Certificate chain not initialized — refresh() has not been called or failed");
        }
        return certChain;
    }

    public void refresh() {
        log.info("Fetching signing certificate from credentials/info for credentialID={}",
            cscProperties.getCredentialId());
        CSCCredentialsInfoResponse response = credentialsInfoClient.getCredentialInfo(
            new CSCCredentialsInfoRequest(cscProperties.getCredentialId())
        );
        try {
            certChain = certificateParser.parseDerCertificates(
                response.getCert().getCertificates()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse signing certificate from credentials/info", e);
        }
        try {
            certificateValidator.validateChain(certChain);
        } catch (SigningException e) {
            throw new IllegalStateException("Certificate validation failed for cached signing certificate", e);
        }
        log.info("Cached signing certificate chain ({} cert(s))", certChain.length);
    }
}
