package com.keepguard.ms_ai_guardian.infrastructure.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class OAuthSecretCrypto {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private OAuthSecretCrypto() {}

    static String decrypt(String secretBase, String encrypted) {
        if (secretBase == null || secretBase.isBlank() || encrypted == null || encrypted.isBlank()) {
            throw new IllegalArgumentException("AUTH_CLIENT_SECRET_BASE e secret cifrado são obrigatórios");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(encrypted);
            if (packed.length <= IV_LENGTH) {
                throw new IllegalArgumentException("secret cifrado incompleto");
            }
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha256(secretBase), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao descriptografar clientSecret", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
