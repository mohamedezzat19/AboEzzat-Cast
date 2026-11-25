package com.aboezzat.share;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Authenticated, certificate-pinned client for the Receiver control API.
 * All requests use HTTPS on the Receiver's TLS-only control port and are
 * signed with the pairing secret using the same canonical format as MainActivity.
 */
public final class ReceiverApiClient {
    private static final int DEFAULT_PORT = 8081;
    private static final String PREFS = "AboEzzatSharePrefs";
    private static final String SECRET_PREFIX = "receiver_auth_secret_";
    private static final String PIN_PREFIX = "receiver_tls_certificate_pin_";
    private static final String KEY_ALIAS = "AboEzzatCast.ReceiverAuthKey";

    private ReceiverApiClient() {}

    public static String buildEndpoint(String address) {
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Empty receiver address");
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        Uri parsed = Uri.parse(value);
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (host == null || host.trim().isEmpty() || parsed.getUserInfo() != null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || (parsed.getPath() != null && !parsed.getPath().isEmpty()
                    && !"/".equals(parsed.getPath()))
                || parsed.getQuery() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("Invalid receiver address");
        }
        int port = parsed.getPort() < 0 ? DEFAULT_PORT : parsed.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid receiver port");
        String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return "https://" + authorityHost + ":" + port;
    }

    public static OkHttpClient create(Context context, String expectedHost) throws Exception {
        if (context == null || expectedHost == null || expectedHost.trim().isEmpty()) {
            throw new IllegalArgumentException("Receiver identity is missing");
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encodedPin = prefs.getString(PIN_PREFIX + expectedHost, "");
        String encryptedSecret = prefs.getString(SECRET_PREFIX + expectedHost, "");
        if (encodedPin == null || !encodedPin.matches("[A-Za-z0-9_-]{43}")) {
            throw new IOException("Receiver certificate has not been pinned");
        }
        byte[] expectedPin = Base64.decode(encodedPin,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (expectedPin.length != 32) throw new IOException("Invalid Receiver certificate pin");
        String secret = decryptSecret(encryptedSecret);
        if (secret.length() < 40) throw new IOException("Receiver pairing secret is unavailable");

        X509TrustManager pinnedTrustManager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("Client certificates are not accepted");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0 || !matchesPin(chain[0], expectedPin)) {
                    throw new java.security.cert.CertificateException("Receiver certificate pin mismatch");
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[]{pinnedTrustManager},
                new java.security.SecureRandom());

        return new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .sslSocketFactory(sslContext.getSocketFactory(), pinnedTrustManager)
                .hostnameVerifier((hostname, session) -> {
                    if (hostname == null || !expectedHost.equalsIgnoreCase(hostname)) return false;
                    try {
                        java.security.cert.Certificate[] peer = session.getPeerCertificates();
                        return peer.length > 0 && peer[0] instanceof X509Certificate
                                && matchesPin((X509Certificate) peer[0], expectedPin);
                    } catch (Exception error) {
                        return false;
                    }
                })
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    if (!original.url().isHttps()
                            || !expectedHost.equalsIgnoreCase(original.url().host())) {
                        throw new IOException("Receiver API requires pinned HTTPS to the paired host");
                    }
                    long timestamp = System.currentTimeMillis();
                    String nonce = UUID.randomUUID().toString();
                    String pathAndQuery = original.url().encodedPath()
                            + (original.url().encodedQuery() == null ? ""
                            : "?" + original.url().encodedQuery());
                    byte[] requestBody = new byte[0];
                    if (original.body() != null) {
                        okio.Buffer buffer = new okio.Buffer();
                        original.body().writeTo(buffer);
                        requestBody = buffer.readByteArray();
                    }
                    String bodyHash = Base64.encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(requestBody),
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    String canonical = original.method() + "\n" + pathAndQuery
                            + "\n" + bodyHash + "\n" + timestamp + "\n" + nonce;
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                    String signature = Base64.encodeToString(
                            mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    Request signed = original.newBuilder()
                            .header("X-AboEzzat-Time", String.valueOf(timestamp))
                            .header("X-AboEzzat-Nonce", nonce)
                            .header("X-AboEzzat-Signature", signature)
                            .build();
                    return chain.proceed(signed);
                })
                .build();
    }

    private static boolean matchesPin(X509Certificate certificate, byte[] expectedPin)
            throws java.security.cert.CertificateException {
        if (certificate == null) return false;
        certificate.checkValidity();
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return MessageDigest.isEqual(actual, expectedPin);
        } catch (java.security.cert.CertificateException error) {
            throw error;
        } catch (Exception error) {
            throw new java.security.cert.CertificateException("Could not verify Receiver certificate", error);
        }
    }

    private static String decryptSecret(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] packedBytes = Base64.decode(encoded, Base64.NO_WRAP);
            ByteBuffer packed = ByteBuffer.wrap(packedBytes);
            int ivLength = packed.getInt();
            if (ivLength < 12 || ivLength > 16 || packed.remaining() <= ivLength) return "";
            byte[] iv = new byte[ivLength];
            packed.get(iv);
            byte[] encrypted = new byte[packed.remaining()];
            packed.get(encrypted);
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) return "";
            javax.crypto.SecretKey key = ((KeyStore.SecretKeyEntry)
                    keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "";
        }
    }
}
