package com.limelight.binding.crypto;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AndroidCryptoProviderTest {
    @Test public void existingRsaKeySignsChallengeAndCertificateFingerprintIsClientCert() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = certificate(pair);
        String challenge = "moonwaker-vibepollo-identity-v1\nchild:profile:nonce";

        byte[] signed = AndroidCryptoProvider.signIdentityChallengeBytes(
                challenge, pair.getPrivate());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(challenge.getBytes(StandardCharsets.UTF_8));

        assertTrue(verifier.verify(signed));
        assertEquals(hex(MessageDigest.getInstance("SHA-256")
                        .digest(certificate.getEncoded())),
                AndroidCryptoProvider.certificateSha256(certificate));
    }

    @Test(expected = IllegalArgumentException.class)
    public void signingRejectsChallengeOutsideGatewayDomain() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        AndroidCryptoProvider.signIdentityChallengeBytes(
                "moonwaker-vibepollo-identity-v1:wrong-domain", generator.generateKeyPair()
                        .getPrivate());
    }

    private static X509Certificate certificate(KeyPair pair) throws Exception {
        Date now = new Date();
        X500Name name = new X500Name("CN=MoonWaker");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, new Date(now.getTime() - 1_000L),
                new Date(now.getTime() + 60_000L), name, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(pair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
