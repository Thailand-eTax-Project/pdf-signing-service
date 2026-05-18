package com.wpanther.pdfsigning.infrastructure.adapter.out.pdf;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v1CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CertificateParser Tests")
class CertificateParserTest {

    private static X509Certificate testCert;
    private final CertificateParser parser = new CertificateParser();

    @BeforeAll
    static void generateTestCertificate() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Name name = new X500Name("CN=Test Cert,O=Test Org,C=TH");
        JcaX509v1CertificateBuilder builder = new JcaX509v1CertificateBuilder(
            name, BigInteger.ONE,
            Date.from(Instant.now().minus(1, ChronoUnit.DAYS)),
            Date.from(Instant.now().plus(365, ChronoUnit.DAYS)),
            name, kp.getPublic()
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        testCert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    @Nested
    @DisplayName("parseDerCertificates()")
    class ParseDerCertificates {

        @Test
        @DisplayName("Should parse a single Base64-DER certificate")
        void shouldParseSingleDerCert() throws Exception {
            String base64Der = Base64.getEncoder().encodeToString(testCert.getEncoded());

            X509Certificate[] result = parser.parseDerCertificates(new String[]{base64Der});

            assertThat(result).hasSize(1);
            assertThat(result[0].getSubjectX500Principal().getName())
                .contains("Test Cert");
        }

        @Test
        @DisplayName("Should parse multiple Base64-DER certificates")
        void shouldParseMultipleDerCerts() throws Exception {
            String base64Der = Base64.getEncoder().encodeToString(testCert.getEncoded());

            X509Certificate[] result = parser.parseDerCertificates(new String[]{base64Der, base64Der});

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Should throw when array is null")
        void shouldThrowForNullArray() {
            assertThatThrownBy(() -> parser.parseDerCertificates(null))
                .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("Should throw when array is empty")
        void shouldThrowForEmptyArray() {
            assertThatThrownBy(() -> parser.parseDerCertificates(new String[0]))
                .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("Should throw when a Base64 entry is not a valid DER certificate")
        void shouldThrowForInvalidDer() {
            assertThatThrownBy(() -> parser.parseDerCertificates(new String[]{"bm90YWNlcnQ="}))
                .isInstanceOf(Exception.class);
        }
    }
}
