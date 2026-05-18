package com.wpanther.pdfsigning.infrastructure.adapter.out.csc;

import com.wpanther.pdfsigning.application.port.out.SigningPort;
import com.wpanther.pdfsigning.domain.model.PadesLevel;
import com.wpanther.pdfsigning.domain.model.SigningException;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.client.CSCApiClient;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.client.CSCAuthClient;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.client.SadTokenValidator;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCAuthorizeRequest;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCAuthorizeResponse;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCSignatureRequest;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCSignatureResponse;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.CertificateParser;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.CertificateValidator;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.PadesCmsBuilder;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.PadesEmbedder;
import com.wpanther.pdfsigning.infrastructure.config.properties.CscProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CscSigningAdapter implements SigningPort {

    private final CSCAuthClient authClient;
    private final CSCApiClient apiClient;
    private final PadesCmsBuilder cmsBuilder;
    private final PadesEmbedder pdfEmbedder;
    private final CertificateParser certificateParser;
    private final CertificateValidator certificateValidator;
    private final SadTokenValidator sadTokenValidator;
    private final CscProperties cscProperties;
    private final CredentialInfoCache credentialInfoCache;

    private static final int NUM_SIGNATURES = 1;

    @Override
    public SigningResult signPdfWithCertChain(byte[] pdfBytes, byte[] digest, PadesLevel padesLevel) {
        log.debug("Starting CSC signing process with PAdES level: {}", padesLevel);
        try {
            // Step 1: Cert chain from cache — no network call
            X509Certificate[] certChain = credentialInfoCache.getCertChain();

            Instant authIssuedAt = Instant.now();
            String base64Digest = Base64.getEncoder().encodeToString(digest);

            // Step 2: Authorize — PIN delivered via authData (not in signHash)
            String pin = cscProperties.getPin();
            List<CSCAuthorizeRequest.AuthDataEntry> authData = (pin != null && !pin.isBlank())
                ? List.of(CSCAuthorizeRequest.AuthDataEntry.builder().id("PIN").value(pin).build())
                : null;

            CSCAuthorizeResponse authResponse = authClient.authorize(
                CSCAuthorizeRequest.builder()
                    .credentialID(cscProperties.getCredentialId())
                    .numSignatures(NUM_SIGNATURES)
                    .hashAlgorithmOID(cscProperties.getHashAlgorithmOid())
                    .hashes(new String[]{base64Digest})
                    .authData(authData)
                    .build()
            );

            sadTokenValidator.validate(authResponse, cscProperties.getCredentialId());

            if (sadTokenValidator.isExpired(authIssuedAt, authResponse.getExpiresIn())) {
                throw new SigningException("SAD token expired between authorization and sign operation");
            }

            // Step 3: Sign — flat hashes[], no clientId, no signatureData wrapper
            CSCSignatureResponse signResponse = apiClient.signHash(
                CSCSignatureRequest.builder()
                    .credentialID(cscProperties.getCredentialId())
                    .SAD(authResponse.getSAD())
                    .hashAlgorithmOID(cscProperties.getHashAlgorithmOid())
                    .hashes(new String[]{base64Digest})
                    .build()
            );

            // Step 4: Build CMS using cached cert (not from response)
            byte[] rawSignature = Base64.getDecoder().decode(signResponse.getSignatures()[0]);
            byte[] cmsSignature = cmsBuilder.buildCmsSignature(rawSignature, certChain, digest);

            // Step 5: Embed into PDF
            byte[] signedPdf = pdfEmbedder.embedSignature(pdfBytes, cmsSignature);

            return new SigningResult(signedPdf, certChain, signResponse.getResponseID(), null);

        } catch (SigningException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error signing PDF with CSC API", e);
            throw new SigningException("Failed to sign PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public void validateCertificateChain(X509Certificate[] certChain) {
        log.debug("Validating certificate chain with {} certificates", certChain.length);
        try {
            certificateValidator.validateChain(certChain);
        } catch (SigningException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error validating certificate chain", e);
            throw new SigningException("Certificate validation failed: " + e.getMessage(), e);
        }
    }
}
