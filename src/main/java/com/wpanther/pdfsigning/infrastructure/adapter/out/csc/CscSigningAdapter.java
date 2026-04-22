package com.wpanther.pdfsigning.infrastructure.adapter.out.csc;

import com.wpanther.pdfsigning.domain.model.PadesLevel;
import com.wpanther.pdfsigning.domain.model.SigningException;
import com.wpanther.pdfsigning.application.port.out.SigningPort;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.client.CSCApiClient;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.client.CSCAuthClient;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.client.SadTokenValidator;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCAuthorizeRequest;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCAuthorizeResponse;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCSignatureRequest;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCSignatureResponse;
import com.wpanther.pdfsigning.infrastructure.config.properties.CscProperties;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.CertificateParser;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.CertificateValidator;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.PadesCmsBuilder;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.PadesEmbedder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;

/**
 * Secondary adapter for CSC-based PDF signing.
 * <p>
 * Implements {@link SigningPort} using the CSC API v2.0 for deferred signing.
 * This adapter encapsulates all CSC-specific logic including:
 * <ul>
 *   <li>Authorization to obtain SAD token</li>
 *   <li>Hash signing via signHash endpoint</li>
 *   <li>CMS/PKCS#7 signature construction</li>
 *   <li>Certificate validation</li>
 * </ul>
 * </p>
 */
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

    /** Number of signatures requested per CSC authorize call. */
    private static final int NUM_SIGNATURES = 1;

    @Override
    public SigningResult signPdfWithCertChain(byte[] pdfBytes, byte[] digest, PadesLevel padesLevel) {
        log.debug("Starting CSC signing process with PAdES level: {}", padesLevel);

        try {
            // Record time before authorize so we can detect expiration before signHash
            Instant authIssuedAt = Instant.now();

            // Step 1: Authorize with CSC API to get SAD token
            // Use standard Base64 encoding — eidasremotesigning uses Base64.getDecoder() (not URL-safe)
            log.debug("Authorizing signing operation with CSC API");
            String base64Digest = Base64.getEncoder().encodeToString(digest);
            CSCAuthorizeResponse authResponse = authClient.authorize(
                CSCAuthorizeRequest.builder()
                    .clientId(cscProperties.getClientId())
                    .credentialID(cscProperties.getCredentialId())
                    .numSignatures(String.valueOf(NUM_SIGNATURES))
                    .hashAlgo(cscProperties.getHashAlgo())
                    .hash(new String[]{base64Digest})
                    .build()
            );

            // Step 2: Validate SAD token (security critical)
            sadTokenValidator.validate(authResponse, cscProperties.getCredentialId());
            log.debug("SAD token validated successfully");

            // Step 3: Sign the hash via CSC API
            // Pass PIN alongside SAD — required by eidasremotesigning to unlock BCFKS private key
            log.debug("Signing hash via CSC API");
            String pin = cscProperties.getPin();
            CSCSignatureRequest.Credentials credentials = (pin != null && !pin.isBlank())
                ? CSCSignatureRequest.Credentials.builder()
                    .pin(CSCSignatureRequest.Credentials.Pin.builder().value(pin).build())
                    .build()
                : null;
            CSCSignatureRequest signRequest = CSCSignatureRequest.builder()
                .clientId(cscProperties.getClientId())
                .credentialID(cscProperties.getCredentialId())
                .SAD(authResponse.getSAD())
                .hashAlgo(cscProperties.getHashAlgo())
                .credentials(credentials)
                .signatureData(CSCSignatureRequest.SignatureData.builder()
                    .hashToSign(new String[]{base64Digest})
                    .build())
                .build();

            // Security critical: verify token hasn't expired since authorization (clock-based)
            if (sadTokenValidator.isExpired(authIssuedAt, authResponse.getExpiresIn())) {
                throw new SigningException(
                    "SAD token expired between authorization and sign operation");
            }

            CSCSignatureResponse signResponse = apiClient.signHash(signRequest);
            log.debug("Received signature from CSC API");

            // Step 4: Parse certificate chain from response
            log.debug("Parsing certificate chain");
            X509Certificate[] responseCertChain = certificateParser.parseCertificateChain(
                signResponse.getCertificate()
            );
            log.debug("Parsed certificate chain: {} certificates", responseCertChain.length);

            // Step 5: Validate certificate chain (security critical)
            log.debug("Validating certificate chain");
            certificateValidator.validateChain(responseCertChain);
            log.debug("Certificate chain validated successfully");

            // Step 6: Build CMS/PKCS#7 signature
            log.debug("Building CMS/PKCS#7 signature");
            byte[] rawSignature = Base64.getDecoder().decode(signResponse.getSignatures()[0]);
            byte[] cmsSignature = cmsBuilder.buildCmsSignature(
                rawSignature,
                responseCertChain,
                digest
            );
            log.debug("Built CMS signature: {} bytes", cmsSignature.length);

            // Step 7: Embed signature into PDF
            log.debug("Embedding signature into PDF");
            byte[] signedPdf = pdfEmbedder.embedSignature(pdfBytes, cmsSignature);
            log.debug("Embedded signature, signed PDF size: {} bytes", signedPdf.length);

            // Return both signed PDF, certificate chain, CSC operation ID (for audit traceability),
            // and trusted timestamp from TSA (if available — only for PAdES-B-T and higher).
            // timestampData is RFC 3161 TimeStampToken; null for PAdES-B-B.
            Instant timestamp = extractTimestamp(signResponse);
            return new SigningResult(signedPdf, responseCertChain, signResponse.getOperationID(), timestamp);

        } catch (SigningException e) {
            // Re-throw domain exceptions as-is
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            log.error("Unexpected error signing PDF with CSC API", e);
            throw new SigningException("Failed to sign PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public void validateCertificateChain(X509Certificate[] certChain) {
        log.debug("Validating certificate chain with {} certificates", certChain.length);
        try {
            certificateValidator.validateChain(certChain);
            log.debug("Certificate chain validated successfully");
        } catch (SigningException e) {
            // Re-throw domain exceptions as-is
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            log.error("Unexpected error validating certificate chain", e);
            throw new SigningException("Certificate validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the trusted timestamp from CSC signHash response's timestampData field.
     *
     * <p>timestampData contains an RFC 3161 TimeStampToken (Base64-encoded) when a trusted
     * timestamp is requested (PAdES-B-T or higher). For PAdES-B-B (no TSA),
     * timestampData is null and this method returns null.</p>
     *
     * <p>The RFC 3161 token is parsed to extract the genTime from the TSTInfo structure.
     * If parsing fails (e.g., invalid format), a warning is logged and null is returned.</p>
     *
     * @param response CSC signHash response
     * @return Instant from TSA, or null if not available or not parsable
     */
    private Instant extractTimestamp(CSCSignatureResponse response) {
        if (response.getTimestampData() == null) {
            return null;
        }
        try {
            byte[] tokenBytes;
            Object tsaData = response.getTimestampData();
            if (tsaData instanceof String tsaString) {
                tokenBytes = Base64.getDecoder().decode(tsaString);
            } else if (tsaData instanceof byte[] bytes) {
                tokenBytes = bytes;
            } else {
                log.warn("timestampData has unexpected type {} — cannot extract timestamp",
                    tsaData.getClass().getName());
                return null;
            }
            // RFC 3161 TimeStampToken: ASN.1 structure with SignedData containing TSTInfo.
            // TSTInfo has an explicit GenTime field.
            return parseRfc3161GenTime(tokenBytes);
        } catch (Exception e) {
            log.warn("Failed to parse timestampData from CSC response: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses RFC 3161 TimeStampToken bytes to extract the genTime.
     *
     * <p>The TimeStampToken is a PKCS#7 SignedData containing TSTInfo.
     * This method uses BouncyCastle to parse the ASN.1 structure and
     * extract the GenTime field.</p>
     */
    private Instant parseRfc3161GenTime(byte[] tokenBytes) {
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(tokenBytes);
            org.bouncycastle.asn1.ASN1InputStream asn1In =
                new org.bouncycastle.asn1.ASN1InputStream(bais);
            org.bouncycastle.asn1.cms.ContentInfo contentInfo =
                org.bouncycastle.asn1.cms.ContentInfo.getInstance(asn1In.readObject());
            asn1In.close();
            org.bouncycastle.tsp.TimeStampToken token =
                new org.bouncycastle.tsp.TimeStampToken(contentInfo);
            return token.getTimeStampInfo().getGenTime().toInstant();
        } catch (Exception e) {
            log.debug("Could not parse RFC 3161 timestamp: {}", e.getMessage());
            return null;
        }
    }
}
