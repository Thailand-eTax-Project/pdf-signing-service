package com.wpanther.pdfsigning.infrastructure.adapter.out.pdf;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Parses certificate chains from CSC responses.
 *
 * Handles both PEM format (-----BEGIN CERTIFICATE-----...-----END CERTIFICATE-----)
 * and raw Base64-encoded DER format as returned by eidasremotesigning.
 */
@Slf4j
@Component
public class CertificateParser {

    /**
     * Parses a certificate chain string into X509Certificate array.
     * Supports both PEM and raw Base64-encoded DER formats.
     */
    public X509Certificate[] parseCertificateChain(String certificateData) throws IOException {
        List<X509Certificate> certificates = new ArrayList<>();

        if (certificateData == null || certificateData.isBlank()) {
            throw new IOException("Certificate data is null or empty");
        }

        String trimmed = certificateData.trim();

        if (trimmed.contains("-----BEGIN")) {
            parsePem(trimmed, certificates);
        } else {
            parseBase64Der(trimmed, certificates);
        }

        if (certificates.isEmpty()) {
            throw new IOException("No certificates found in certificate data");
        }

        log.info("Parsed certificate chain with {} certificates", certificates.size());
        return certificates.toArray(new X509Certificate[0]);
    }

    private void parsePem(String pemData, List<X509Certificate> certificates) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(pemData.getBytes(StandardCharsets.UTF_8));
             InputStreamReader isr = new InputStreamReader(bais, StandardCharsets.UTF_8);
             PEMParser parser = new PEMParser(isr)) {

            Object object;
            while ((object = parser.readObject()) != null) {
                if (object instanceof X509CertificateHolder) {
                    X509CertificateHolder holder = (X509CertificateHolder) object;
                    try {
                        X509Certificate cert = new JcaX509CertificateConverter()
                            .getCertificate(holder);
                        certificates.add(cert);
                        log.debug("Parsed PEM certificate: {}", cert.getSubjectDN());
                    } catch (CertificateException e) {
                        throw new IOException("Failed to convert certificate", e);
                    }
                }
            }
        }
    }

    private void parseBase64Der(String base64Data, List<X509Certificate> certificates) throws IOException {
        try {
            byte[] derBytes = Base64.getDecoder().decode(base64Data);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            try (ByteArrayInputStream bais = new ByteArrayInputStream(derBytes)) {
                X509Certificate cert = (X509Certificate) factory.generateCertificate(bais);
                certificates.add(cert);
                log.debug("Parsed Base64 DER certificate: {}", cert.getSubjectDN());
            }
        } catch (CertificateException e) {
            throw new IOException("Failed to parse Base64 DER certificate", e);
        }
    }

    /**
     * Gets the end-entity (signing) certificate from the chain.
     * This is typically the first certificate in the chain.
     *
     * @param chain Certificate chain
     * @return The signing certificate or null if chain is empty
     */
    public X509Certificate getSigningCertificate(X509Certificate[] chain) {
        return chain.length > 0 ? chain[0] : null;
    }

    /**
     * Gets the issuer (CA) certificate from the chain.
     * This is typically the last certificate in the chain.
     *
     * @param chain Certificate chain
     * @return The issuer certificate or null if chain has only one certificate
     */
    public X509Certificate getIssuerCertificate(X509Certificate[] chain) {
        return chain.length > 1 ? chain[chain.length - 1] : null;
    }

    /**
     * Parses an array of Base64-encoded DER certificates as returned by CSC credentials/info.
     */
    public X509Certificate[] parseDerCertificates(String[] base64DerCerts) throws IOException {
        if (base64DerCerts == null || base64DerCerts.length == 0) {
            throw new IOException("Certificate array is null or empty");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate[] chain = new X509Certificate[base64DerCerts.length];
            for (int i = 0; i < base64DerCerts.length; i++) {
                byte[] derBytes = Base64.getDecoder().decode(base64DerCerts[i]);
                try (ByteArrayInputStream bais = new ByteArrayInputStream(derBytes)) {
                    chain[i] = (X509Certificate) factory.generateCertificate(bais);
                }
            }
            log.info("Parsed {} DER certificate(s) from credentials/info", chain.length);
            return chain;
        } catch (CertificateException e) {
            throw new IOException("Failed to parse DER certificate array", e);
        }
    }
}
