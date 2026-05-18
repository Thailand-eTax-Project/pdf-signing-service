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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CscSigningAdapter Tests")
class CscSigningAdapterTest {

    @Mock private CSCAuthClient mockAuthClient;
    @Mock private CSCApiClient mockApiClient;
    @Mock private PadesCmsBuilder mockCmsBuilder;
    @Mock private PadesEmbedder mockPdfEmbedder;
    @Mock private CertificateParser mockCertificateParser;
    @Mock private CertificateValidator mockCertificateValidator;
    @Mock private SadTokenValidator mockSadTokenValidator;
    @Mock private CscProperties mockCscProperties;
    @Mock private CredentialInfoCache mockCredentialInfoCache;

    private CscSigningAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CscSigningAdapter(
            mockAuthClient,
            mockApiClient,
            mockCmsBuilder,
            mockPdfEmbedder,
            mockCertificateParser,
            mockCertificateValidator,
            mockSadTokenValidator,
            mockCscProperties,
            mockCredentialInfoCache
        );
        lenient().when(mockCscProperties.getCredentialId()).thenReturn("test-credential");
        lenient().when(mockCscProperties.getHashAlgorithmOid()).thenReturn("2.16.840.1.101.3.4.2.1");
        lenient().when(mockCscProperties.getPin()).thenReturn("");
        lenient().when(mockCredentialInfoCache.getCertChain()).thenReturn(new X509Certificate[0]);
    }

    @Nested
    @DisplayName("signPdfWithCertChain()")
    class SignPdfMethod {

        @Test
        @DisplayName("Should sign PDF successfully through the complete v2.0 flow")
        void shouldSignPdfSuccessfully() throws Exception {
            byte[] pdfBytes = "test pdf content".getBytes();
            byte[] digest = new byte[32];

            CSCAuthorizeResponse authResponse = new CSCAuthorizeResponse();
            authResponse.setSAD("test-sad-token");

            CSCSignatureResponse signResponse = CSCSignatureResponse.builder()
                .signatures(new String[]{Base64.getEncoder().encodeToString("raw-sig".getBytes())})
                .responseID("resp-001")
                .build();

            byte[] cmsSignature = "cms-signature".getBytes();
            byte[] signedPdf = "signed-pdf-content".getBytes();

            when(mockAuthClient.authorize(any())).thenReturn(authResponse);
            when(mockApiClient.signHash(any())).thenReturn(signResponse);
            when(mockCmsBuilder.buildCmsSignature(any(), any(), any())).thenReturn(cmsSignature);
            when(mockPdfEmbedder.embedSignature(any(), any())).thenReturn(signedPdf);

            SigningPort.SigningResult result =
                adapter.signPdfWithCertChain(pdfBytes, digest, PadesLevel.BASELINE_B);

            assertThat(result.signedPdf()).isEqualTo(signedPdf);
            assertThat(result.transactionId()).isEqualTo("resp-001");
            verify(mockCredentialInfoCache).getCertChain();
            // cert no longer comes from signHash response — parseCertificateChain must NOT be called
            verify(mockCertificateParser, never()).parseCertificateChain(any());
        }

        @Test
        @DisplayName("authorize request must use hashes[], hashAlgorithmOID, Integer numSignatures — no clientId")
        void shouldSendCorrectAuthorizeRequest() throws Exception {
            byte[] pdfBytes = "pdf".getBytes();
            byte[] digest = new byte[32];

            CSCAuthorizeResponse authResponse = new CSCAuthorizeResponse();
            authResponse.setSAD("sad");

            CSCSignatureResponse signResponse = CSCSignatureResponse.builder()
                .signatures(new String[]{Base64.getEncoder().encodeToString("sig".getBytes())})
                .build();

            when(mockAuthClient.authorize(any())).thenReturn(authResponse);
            when(mockApiClient.signHash(any())).thenReturn(signResponse);
            when(mockCmsBuilder.buildCmsSignature(any(), any(), any())).thenReturn(new byte[8]);
            when(mockPdfEmbedder.embedSignature(any(), any())).thenReturn(new byte[8]);

            adapter.signPdfWithCertChain(pdfBytes, digest, PadesLevel.BASELINE_B);

            ArgumentCaptor<CSCAuthorizeRequest> captor =
                ArgumentCaptor.forClass(CSCAuthorizeRequest.class);
            verify(mockAuthClient).authorize(captor.capture());
            CSCAuthorizeRequest req = captor.getValue();

            assertThat(req.getCredentialID()).isEqualTo("test-credential");
            assertThat(req.getNumSignatures()).isEqualTo(1);
            assertThat(req.getHashAlgorithmOID()).isEqualTo("2.16.840.1.101.3.4.2.1");
            assertThat(req.getHashes()).isNotEmpty();
            assertThat(req.getAuthData()).isNull(); // no PIN configured
        }

        @Test
        @DisplayName("authorize request must include authData with PIN when pin is configured")
        void shouldIncludeAuthDataWhenPinConfigured() throws Exception {
            when(mockCscProperties.getPin()).thenReturn("1234");

            byte[] pdfBytes = "pdf".getBytes();
            byte[] digest = new byte[32];

            CSCAuthorizeResponse authResponse = new CSCAuthorizeResponse();
            authResponse.setSAD("sad");

            CSCSignatureResponse signResponse = CSCSignatureResponse.builder()
                .signatures(new String[]{Base64.getEncoder().encodeToString("sig".getBytes())})
                .build();

            when(mockAuthClient.authorize(any())).thenReturn(authResponse);
            when(mockApiClient.signHash(any())).thenReturn(signResponse);
            when(mockCmsBuilder.buildCmsSignature(any(), any(), any())).thenReturn(new byte[8]);
            when(mockPdfEmbedder.embedSignature(any(), any())).thenReturn(new byte[8]);

            adapter.signPdfWithCertChain(pdfBytes, digest, PadesLevel.BASELINE_B);

            ArgumentCaptor<CSCAuthorizeRequest> captor =
                ArgumentCaptor.forClass(CSCAuthorizeRequest.class);
            verify(mockAuthClient).authorize(captor.capture());
            CSCAuthorizeRequest req = captor.getValue();

            assertThat(req.getAuthData()).isNotNull().hasSize(1);
            assertThat(req.getAuthData().get(0).getId()).isEqualTo("PIN");
            assertThat(req.getAuthData().get(0).getValue()).isEqualTo("1234");
        }

        @Test
        @DisplayName("signHash request must use flat hashes[], hashAlgorithmOID — no clientId, no signatureData wrapper")
        void shouldSendCorrectSignHashRequest() throws Exception {
            byte[] pdfBytes = "pdf".getBytes();
            byte[] digest = new byte[32];

            CSCAuthorizeResponse authResponse = new CSCAuthorizeResponse();
            authResponse.setSAD("the-sad");

            CSCSignatureResponse signResponse = CSCSignatureResponse.builder()
                .signatures(new String[]{Base64.getEncoder().encodeToString("sig".getBytes())})
                .build();

            when(mockAuthClient.authorize(any())).thenReturn(authResponse);
            when(mockApiClient.signHash(any())).thenReturn(signResponse);
            when(mockCmsBuilder.buildCmsSignature(any(), any(), any())).thenReturn(new byte[8]);
            when(mockPdfEmbedder.embedSignature(any(), any())).thenReturn(new byte[8]);

            adapter.signPdfWithCertChain(pdfBytes, digest, PadesLevel.BASELINE_B);

            ArgumentCaptor<CSCSignatureRequest> captor =
                ArgumentCaptor.forClass(CSCSignatureRequest.class);
            verify(mockApiClient).signHash(captor.capture());
            CSCSignatureRequest req = captor.getValue();

            assertThat(req.getCredentialID()).isEqualTo("test-credential");
            assertThat(req.getSAD()).isEqualTo("the-sad");
            assertThat(req.getHashes()).isNotEmpty();
            assertThat(req.getHashAlgorithmOID()).isEqualTo("2.16.840.1.101.3.4.2.1");
        }

        @Test
        @DisplayName("Should throw SigningException when SAD token expires before signHash")
        void shouldThrowWhenSadTokenExpiredBeforeSignHash() {
            byte[] pdfBytes = "pdf".getBytes();
            byte[] digest = new byte[32];

            CSCAuthorizeResponse authResponse = new CSCAuthorizeResponse();
            authResponse.setSAD("test-sad-token");
            authResponse.setExpiresIn(1L);

            when(mockAuthClient.authorize(any())).thenReturn(authResponse);
            when(mockSadTokenValidator.isExpired(any(Instant.class), any(Long.class))).thenReturn(true);

            assertThatThrownBy(() -> adapter.signPdfWithCertChain(pdfBytes, digest, PadesLevel.BASELINE_B))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("expired");

            verify(mockApiClient, never()).signHash(any());
        }

        @Test
        @DisplayName("Should propagate authorization exceptions as SigningException")
        void shouldPropagateAuthException() {
            when(mockAuthClient.authorize(any()))
                .thenThrow(new RuntimeException("Auth failed"));

            assertThatThrownBy(() ->
                adapter.signPdfWithCertChain("pdf".getBytes(), new byte[32], PadesLevel.BASELINE_B))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Failed to sign PDF");
        }

        @Test
        @DisplayName("Should propagate signing exceptions as SigningException")
        void shouldPropagateSigningException() {
            CSCAuthorizeResponse authResponse = new CSCAuthorizeResponse();
            authResponse.setSAD("sad");
            when(mockAuthClient.authorize(any())).thenReturn(authResponse);
            when(mockApiClient.signHash(any())).thenThrow(new RuntimeException("Signing failed"));

            assertThatThrownBy(() ->
                adapter.signPdfWithCertChain("pdf".getBytes(), new byte[32], PadesLevel.BASELINE_B))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Failed to sign PDF");
        }
    }

    @Nested
    @DisplayName("validateCertificateChain()")
    class ValidateCertificateChainMethod {

        @Test
        @DisplayName("Should validate certificate chain successfully")
        void shouldValidateCertificateChain() {
            X509Certificate[] certChain = new X509Certificate[0];
            doNothing().when(mockCertificateValidator).validateChain(certChain);

            adapter.validateCertificateChain(certChain);

            verify(mockCertificateValidator).validateChain(certChain);
        }

        @Test
        @DisplayName("Should propagate validation exceptions as SigningException")
        void shouldPropagateValidationException() {
            X509Certificate[] certChain = new X509Certificate[0];
            doThrow(new RuntimeException("Validation failed"))
                .when(mockCertificateValidator).validateChain(certChain);

            assertThatThrownBy(() -> adapter.validateCertificateChain(certChain))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Certificate validation failed");
        }
    }
}
