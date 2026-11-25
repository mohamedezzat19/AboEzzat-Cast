package com.aboezzat.share;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.security.spec.ECGenParameterSpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.security.auth.x500.X500Principal;

/** Per-install TLS identity for the local media server. */
public final class CastTlsIdentity {
    private static final String KEY_ALIAS = "AboEzzatCast.MediaTlsIdentity.v3.EC";
    // Keep the previous RSA alias intact so rollback remains possible.
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private CastTlsIdentity() { }

    private static synchronized KeyStore loadIdentityStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            long now = System.currentTimeMillis();
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setKeySize(256)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(new X500Principal("CN=AboEzzat Cast Media"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(new Date(now - 24L * 60L * 60L * 1000L))
                    .setCertificateNotAfter(new Date(now + 10L * 365L * 24L * 60L * 60L * 1000L))
                    .setUserAuthenticationRequired(false).build();
            generator.initialize(spec);
            generator.generateKeyPair();
            keyStore.load(null);
        }
        Certificate generated = keyStore.getCertificate(KEY_ALIAS);
        if (!(generated instanceof X509Certificate)
                || !"EC".equalsIgnoreCase(((X509Certificate) generated).getPublicKey().getAlgorithm())) {
            throw new IllegalStateException("Cast TLS v3 identity is not a valid EC certificate");
        }
        return keyStore;
    }

    private static X509Certificate getCertificate() throws Exception {
        Certificate certificate = loadIdentityStore().getCertificate(KEY_ALIAS);
        if (!(certificate instanceof X509Certificate)) throw new IllegalStateException("Cast TLS certificate is unavailable");
        X509Certificate x509 = (X509Certificate) certificate;
        x509.checkValidity();
        if (!"EC".equalsIgnoreCase(x509.getPublicKey().getAlgorithm())) throw new IllegalStateException("Cast TLS identity is not EC");
        return x509;
    }

    public static String getCertificateFingerprint() throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(getCertificate().getEncoded());
        return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public static synchronized SSLServerSocketFactory getServerSocketFactory() throws Exception {
        KeyStore identity = loadIdentityStore();
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(identity, null);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return context.getServerSocketFactory();
    }
}