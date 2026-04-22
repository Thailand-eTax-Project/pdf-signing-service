package com.wpanther.pdfsigning.infrastructure.adapter.out.pdf;

import com.wpanther.pdfsigning.domain.model.SigningException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.*;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.Store;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Builds CMS/PKCS#7 signature containers for PAdES signatures.
 *
 * <p>Takes a raw signature from the CSC API and constructs a standards-compliant
 * CMS envelope with PAdES signed attributes per ETSI EN 319 142-1.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PadesCmsBuilder {

    /**
     * Builds a CMS/PKCS#7 signature container with the raw signature from CSC.
     *
     * <p>Steps:
     * <ol>
     *   <li>Create a CMSSignedDataGenerator and add the certificate chain</li>
     *   <li>Create signer info with the raw signature (algorithm derived from cert key type)</li>
     *   <li>Add PAdES compliant signed attributes</li>
     *   <li>Generate the CMS structure</li>
     * </ol>
     *
     * @param rawSignature Raw signature bytes from CSC signHash endpoint
     * @param certChain    Certificate chain from CSC response
     * @param digest       The PDF byte range digest that was signed
     * @return Encoded CMS/PKCS#7 signature bytes
     * @throws SigningException if CMS construction fails
     */
    public byte[] buildCmsSignature(byte[] rawSignature,
                                    X509Certificate[] certChain,
                                    byte[] digest) throws SigningException {

        try {
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();

            Store certStore = new JcaCertStore(Arrays.asList(certChain));
            generator.addCertificates(certStore);

            ContentSigner signer = new PrecomputedContentSigner(rawSignature, certChain[0]);

            DigestCalculatorProvider digestProvider =
                new JcaDigestCalculatorProviderBuilder().build();

            SignerInfoGeneratorBuilder signerBuilder =
                new SignerInfoGeneratorBuilder(digestProvider);

            signerBuilder.setSignedAttributeGenerator(
                new PadesSignedAttributesGenerator(certChain[0], digest)
            );

            generator.addSignerInfoGenerator(
                signerBuilder.build(signer, new X509CertificateHolder(
                    certChain[0].getEncoded()))
            );

            CMSSignedData cms = generator.generate(
                new CMSProcessableByteArray(digest), false
            );

            return cms.getEncoded();
        } catch (SigningException e) {
            throw e;
        } catch (Exception e) {
            throw new SigningException("Failed to build CMS signature: " + e.getMessage(), e);
        }
    }

    /**
     * ContentSigner that returns a pre-computed signature from the CSC service.
     *
     * <p>The CMS algorithm identifier is derived from the signer certificate's
     * public key type: RSA → SHA256withRSA, EC → SHA256withECDSA.</p>
     */
    static class PrecomputedContentSigner implements ContentSigner {

        private final byte[] signature;
        private final AlgorithmIdentifier algorithmIdentifier;
        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        PrecomputedContentSigner(byte[] signature, X509Certificate signerCert) {
            this.signature = signature;
            this.algorithmIdentifier = deriveAlgorithmIdentifier(signerCert);
        }

        private static AlgorithmIdentifier deriveAlgorithmIdentifier(X509Certificate cert) {
            String keyAlg = cert.getPublicKey().getAlgorithm();
            return switch (keyAlg) {
                case "RSA" -> new AlgorithmIdentifier(
                    new ASN1ObjectIdentifier("1.2.840.113549.1.1.11") // SHA256withRSA
                );
                case "EC" -> new AlgorithmIdentifier(
                    new ASN1ObjectIdentifier("1.2.840.10045.4.3.2") // SHA256withECDSA
                );
                default -> throw new SigningException("Unsupported signing key algorithm: " + keyAlg);
            };
        }

        @Override
        public byte[] getSignature() {
            return signature;
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return algorithmIdentifier;
        }
    }

    /**
     * Generates PAdES compliant signed attributes per ETSI EN 319 142-1.
     *
     * <p>Required attributes:
     * <ul>
     *   <li>contentType (id-data): 1.2.840.113549.1.9.3</li>
     *   <li>messageDigest: 1.2.840.113549.1.9.4</li>
     *   <li>signingTime: 1.2.840.113549.1.9.5</li>
     *   <li>signingCertificateV2: 1.2.840.113549.1.9.16.2.47</li>
     * </ul>
     */
    static class PadesSignedAttributesGenerator implements CMSAttributeTableGenerator {

        private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";
        private final X509Certificate certificate;
        private final byte[] digest;

        PadesSignedAttributesGenerator(X509Certificate certificate, byte[] digest) {
            this.certificate = certificate;
            this.digest = digest;
        }

        @Override
        @SuppressWarnings("rawtypes") // BouncyCastle CMSAttributeTableGenerator declares raw Map; no generic variant exists
        public AttributeTable getAttributes(Map params) {
            try {
                ASN1Sequence signingCertV2 = buildSigningCertificateV2();

                Hashtable<ASN1ObjectIdentifier, ASN1Encodable> attributes = new Hashtable<>();
                attributes.put(
                    PKCSObjectIdentifiers.pkcs_9_at_contentType,
                    new Attribute(
                        PKCSObjectIdentifiers.pkcs_9_at_contentType,
                        new DERSet(new ASN1ObjectIdentifier("1.2.840.113549.1.7.1")))
                );
                attributes.put(
                    PKCSObjectIdentifiers.pkcs_9_at_messageDigest,
                    new Attribute(
                        PKCSObjectIdentifiers.pkcs_9_at_messageDigest,
                        new DERSet(new DEROctetString(digest)))
                );
                attributes.put(
                    PKCSObjectIdentifiers.pkcs_9_at_signingTime,
                    new Attribute(
                        PKCSObjectIdentifiers.pkcs_9_at_signingTime,
                        new DERSet(new ASN1UTCTime(new Date())))
                );
                attributes.put(
                    PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                    new Attribute(
                        PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                        new DERSet(signingCertV2))
                );

                return new AttributeTable(attributes);

            } catch (SigningException e) {
                throw e;
            } catch (Exception e) {
                throw new SigningException("Failed to build PAdES signed attributes", e);
            }
        }

        private ASN1Sequence buildSigningCertificateV2() throws SigningException {
            byte[] certDigest = computeCertificateDigest(certificate);

            ASN1EncodableVector certIdVector = new ASN1EncodableVector();
            certIdVector.add(new AlgorithmIdentifier(
                new ASN1ObjectIdentifier(SHA256_OID)
            ));
            certIdVector.add(new DEROctetString(certDigest));

            return new DERSequence(new DERSequence(certIdVector));
        }

        private byte[] computeCertificateDigest(X509Certificate cert) throws SigningException {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return md.digest(cert.getEncoded());
            } catch (Exception e) {
                throw new SigningException("Failed to compute certificate digest", e);
            }
        }
    }
}
