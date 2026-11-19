package com.boongboongus.backend.domain.push;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push 메시지 암호화(RFC 8291, aes128gcm 단일 레코드)와 P-256 키 변환. JDK 암호화 API만 쓴다.
 */
final class WebPushCrypto {
    static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {
    }

    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        try {
            KeyPair server = generateKeyPair();
            byte[] asPublic = encodePoint((ECPublicKey) server.getPublic());
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            return encrypt(plaintext, uaPublic, authSecret, server, asPublic, salt);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Web push encryption failed", exception);
        }
    }

    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair server, byte[] asPublic,
            byte[] salt) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(server.getPrivate());
        agreement.doPhase(publicKey(uaPublic), true);
        byte[] ecdhSecret = agreement.generateSecret();

        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] ikm = hmac(prkKey, concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0},
                uaPublic, asPublic, new byte[] {1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, info("aes128gcm")), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, info("nonce")), 12);

        if (plaintext.length + 1 + 16 > RECORD_SIZE - 86) {
            throw new IllegalArgumentException("Web push payload too large");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        // 마지막(유일한) 레코드 구분자 0x02
        byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] {2}));

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), ciphertext);
    }

    static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Invalid P-256 public key");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    static ECPrivateKey privateKey(byte[] d) throws GeneralSecurityException {
        if (d.length != 32) {
            throw new IllegalArgumentException("Invalid P-256 private key");
        }
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
    }

    static byte[] encodePoint(ECPublicKey key) {
        return concat(new byte[] {4}, unsigned(key.getW().getAffineX()), unsigned(key.getW().getAffineY()));
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] info(String type) {
        return concat(("Content-Encoding: " + type).getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1});
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, fixed, 32 - length, length);
        return fixed;
    }

    private static ECParameterSpec p256() {
        try {
            return ((ECPublicKey) generateKeyPair().getPublic()).getParams();
        } catch (GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
