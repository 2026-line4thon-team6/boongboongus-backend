package com.boongboongus.backend.domain.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebPushCryptoTest {
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    // RFC 8291 Appendix A 예시 값
    @Test
    void matchesRfc8291Example() throws Exception {
        byte[] plaintext = "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8);
        byte[] asPublic = B64.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        byte[] asPrivate = B64.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw");
        byte[] uaPublic = B64.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] auth = B64.decode("BTBZMqHH6r4Tts7J_aSIgg");
        byte[] salt = B64.decode("DGv6ra1nlYgDCS1FRnbzlw");
        KeyPair server = new KeyPair(WebPushCrypto.publicKey(asPublic), WebPushCrypto.privateKey(asPrivate));

        byte[] body = WebPushCrypto.encrypt(plaintext, uaPublic, auth, server, asPublic, salt);

        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(body)).isEqualTo(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    // 무작위 키 · salt로 암호화해도 받는 쪽(브라우저)이 RFC 8291 순서대로 풀 수 있다
    @Test
    void receiverCanDecryptRandomizedMessage() throws Exception {
        KeyPair receiver = WebPushCrypto.generateKeyPair();
        byte[] uaPublic = WebPushCrypto.encodePoint((java.security.interfaces.ECPublicKey) receiver.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        byte[] message = "{\"title\":\"민지님과 커플이 연결됐어요\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = WebPushCrypto.encrypt(message, uaPublic, auth);

        assertThat(decrypt(body, receiver, uaPublic, auth)).isEqualTo(message);
    }

    @Test
    void rejectsPayloadThatDoesNotFitOneRecord() {
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[4000], validPublicKey(), new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] validPublicKey() throws Exception {
        return WebPushCrypto.encodePoint((java.security.interfaces.ECPublicKey) WebPushCrypto.generateKeyPair().getPublic());
    }

    private static byte[] decrypt(byte[] body, KeyPair receiver, byte[] uaPublic, byte[] auth) throws Exception {
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int idLength = body[20];
        byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
        byte[] ciphertext = Arrays.copyOfRange(body, 21 + idLength, body.length);

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(receiver.getPrivate());
        agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] prkKey = WebPushCrypto.hmac(auth, agreement.generateSecret());
        byte[] ikm = WebPushCrypto.hmac(prkKey, WebPushCrypto.concat("WebPush: info".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0}, uaPublic, asPublic, new byte[] {1}));
        byte[] prk = WebPushCrypto.hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.info("aes128gcm")), 16);
        byte[] nonce = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.info("nonce")), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);
        assertThat(padded[padded.length - 1]).isEqualTo((byte) 2);
        return Arrays.copyOf(padded, padded.length - 1);
    }
}
