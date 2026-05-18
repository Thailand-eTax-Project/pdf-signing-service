package com.wpanther.pdfsigning.infrastructure.adapter.out.csc;

import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.client.CSCCredentialsInfoClient;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCCredentialsInfoRequest;
import com.wpanther.pdfsigning.infrastructure.adapter.out.csc.dto.CSCCredentialsInfoResponse;
import com.wpanther.pdfsigning.infrastructure.adapter.out.pdf.CertificateParser;
import com.wpanther.pdfsigning.infrastructure.config.properties.CscProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialInfoCache Tests")
class CredentialInfoCacheTest {

    @Mock private CSCCredentialsInfoClient mockClient;
    @Mock private CertificateParser mockParser;
    @Mock private CscProperties mockProperties;

    private CredentialInfoCache cache;

    @BeforeEach
    void setUp() {
        cache = new CredentialInfoCache(mockClient, mockParser, mockProperties);
        when(mockProperties.getCredentialId()).thenReturn("test-cred");
    }

    @Nested
    @DisplayName("refresh()")
    class RefreshMethod {

        @Test
        @DisplayName("Should call credentials/info with configured credentialID and cache result")
        void shouldFetchAndCacheCertChain() throws IOException {
            X509Certificate[] expectedChain = new X509Certificate[1];
            CSCCredentialsInfoResponse response = buildResponse("certBase64");
            when(mockClient.getCredentialInfo(any())).thenReturn(response);
            when(mockParser.parseDerCertificates(new String[]{"certBase64"})).thenReturn(expectedChain);

            cache.refresh();

            ArgumentCaptor<CSCCredentialsInfoRequest> captor =
                ArgumentCaptor.forClass(CSCCredentialsInfoRequest.class);
            verify(mockClient).getCredentialInfo(captor.capture());
            assertThat(captor.getValue().getCredentialID()).isEqualTo("test-cred");
            assertThat(cache.getCertChain()).isSameAs(expectedChain);
        }

        @Test
        @DisplayName("getCertChain() returns cached value without additional client calls")
        void shouldNotCallClientOnSubsequentGet() throws IOException {
            X509Certificate[] chain = new X509Certificate[1];
            when(mockClient.getCredentialInfo(any())).thenReturn(buildResponse("cert"));
            when(mockParser.parseDerCertificates(any())).thenReturn(chain);

            cache.refresh();
            cache.getCertChain();
            cache.getCertChain();

            verify(mockClient, times(1)).getCredentialInfo(any());
        }

        @Test
        @DisplayName("refresh() again replaces the cached chain")
        void shouldUpdateCacheOnSecondRefresh() throws IOException {
            X509Certificate[] first = new X509Certificate[1];
            X509Certificate[] second = new X509Certificate[2];
            when(mockClient.getCredentialInfo(any())).thenReturn(buildResponse("cert"));
            when(mockParser.parseDerCertificates(any()))
                .thenReturn(first)
                .thenReturn(second);

            cache.refresh();
            assertThat(cache.getCertChain()).isSameAs(first);

            cache.refresh();
            assertThat(cache.getCertChain()).isSameAs(second);
        }

        @Test
        @DisplayName("Should propagate exception when client throws")
        void shouldPropagateClientException() {
            when(mockClient.getCredentialInfo(any()))
                .thenThrow(new RuntimeException("CSC unavailable"));

            assertThatThrownBy(() -> cache.refresh())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("CSC unavailable");
        }
    }

    private CSCCredentialsInfoResponse buildResponse(String base64DerCert) {
        CSCCredentialsInfoResponse.CertInfo certInfo =
            new CSCCredentialsInfoResponse.CertInfo(new String[]{base64DerCert});
        return new CSCCredentialsInfoResponse(certInfo);
    }
}
