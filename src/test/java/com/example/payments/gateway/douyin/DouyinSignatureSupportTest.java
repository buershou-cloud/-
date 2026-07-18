package com.example.payments.gateway.douyin;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class DouyinSignatureSupportTest {

    @Test
    void requestSignatureUsesTheOfficialCanonicalMessage() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String message = DouyinPayClient.requestMessage(
                "POST",
                "/v1/trade/transactions/h5?demo=1",
                "1721300000",
                "12345678901234567890123456789012",
                "{\"amount\":{\"total\":100}}"
        );

        assertThat(message).isEqualTo(
                "POST\n/v1/trade/transactions/h5?demo=1\n1721300000\n"
                        + "12345678901234567890123456789012\n{\"amount\":{\"total\":100}}\n"
        );
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(DouyinSignatureSupport.sign(message, privateKey))))
                .isTrue();
    }

    @Test
    void decryptsAesGcmNotificationResource() throws Exception {
        String key = "12345678901234567890123456789012";
        String nonce = "0123456789ab";
        String associatedData = "transaction";
        String plainText = "{\"out_trade_no\":\"ORDER-1001\"}";

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8))
        );
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));

        assertThat(DouyinSignatureSupport.decrypt(associatedData, nonce, ciphertext, key)).isEqualTo(plainText);
    }
}
