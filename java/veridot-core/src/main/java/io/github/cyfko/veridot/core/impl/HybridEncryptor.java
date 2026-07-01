package io.github.cyfko.veridot.core.impl;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Utility for performing hybrid encryption (AES-256-GCM + ECIES/RSA) for SECURE_PAYLOAD entries (§12).
 */
public final class HybridEncryptor {

    public static byte[] encryptSymmetric(byte[] plaintext, byte[] key, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(plaintext);
    }

    public static byte[] decryptSymmetric(byte[] ciphertext, byte[] key, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(ciphertext);
    }

    public static byte[] encryptAsymmetric(byte[] symmetricKey, PublicKey publicKey) throws Exception {
        String alg = publicKey.getAlgorithm();
        if ("RSA".equalsIgnoreCase(alg)) {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            OAEPParameterSpec oaepParams = new OAEPParameterSpec(
                "SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
            );
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams);
            return cipher.doFinal(symmetricKey);
        } else if ("EC".equalsIgnoreCase(alg)) {
            // Generate ephemeral EC keypair
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(256);
            KeyPair ephemeral = kpg.generateKeyPair();

            // ECDH
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(ephemeral.getPrivate());
            ka.doPhase(publicKey, true);
            byte[] sharedSecret = ka.generateSecret();

            // Derive wrapper key using HKDF
            byte[] wrapperKeyBytes = hkdfSha256(
                sharedSecret,
                "ecies-wrapper-v4".getBytes(StandardCharsets.UTF_8),
                "AES-GCM-key".getBytes(StandardCharsets.UTF_8),
                32
            );

            // Encrypt symmetricKey with wrapperKey using AES-GCM
            SecureRandom sr = new SecureRandom();
            byte[] iv = new byte[12];
            sr.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(wrapperKeyBytes, "AES"),
                new GCMParameterSpec(128, iv));
            byte[] encSymmetricKey = cipher.doFinal(symmetricKey);

            // Structure: [ephemeralPubKeyLen (2 bytes)] + [ephemeralPubKey (variable)] + [iv (12 bytes)] + [encSymmetricKey (variable)]
            byte[] pubBytes = ephemeral.getPublic().getEncoded();
            byte[] result = new byte[2 + pubBytes.length + 12 + encSymmetricKey.length];
            result[0] = (byte) ((pubBytes.length >> 8) & 0xFF);
            result[1] = (byte) (pubBytes.length & 0xFF);
            System.arraycopy(pubBytes, 0, result, 2, pubBytes.length);
            System.arraycopy(iv, 0, result, 2 + pubBytes.length, 12);
            System.arraycopy(encSymmetricKey, 0, result, 2 + pubBytes.length + 12, encSymmetricKey.length);
            return result;
        } else {
            throw new UnsupportedOperationException("Asymmetric encryption not supported for algorithm: " + alg);
        }
    }

    public static byte[] decryptAsymmetric(byte[] encryptedKey, PrivateKey privateKey) throws Exception {
        String alg = privateKey.getAlgorithm();
        if ("RSA".equalsIgnoreCase(alg)) {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            OAEPParameterSpec oaepParams = new OAEPParameterSpec(
                "SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
            );
            cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepParams);
            return cipher.doFinal(encryptedKey);
        } else if ("EC".equalsIgnoreCase(alg)) {
            if (encryptedKey.length < 14) {
                throw new IllegalArgumentException("Invalid encryptedKey length for EC");
            }
            int pubLen = ((encryptedKey[0] & 0xFF) << 8) | (encryptedKey[1] & 0xFF);
            if (2 + pubLen + 12 > encryptedKey.length) {
                throw new IllegalArgumentException("Invalid ephemeral public key or IV length");
            }
            byte[] pubBytes = Arrays.copyOfRange(encryptedKey, 2, 2 + pubLen);
            byte[] iv = Arrays.copyOfRange(encryptedKey, 2 + pubLen, 2 + pubLen + 12);
            byte[] encSymmetricKey = Arrays.copyOfRange(encryptedKey, 2 + pubLen + 12, encryptedKey.length);

            // Reconstruct ephemeral public key
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey ephemeralPubKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

            // ECDH
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(privateKey);
            ka.doPhase(ephemeralPubKey, true);
            byte[] sharedSecret = ka.generateSecret();

            // Derive wrapper key using HKDF
            byte[] wrapperKeyBytes = hkdfSha256(
                sharedSecret,
                "ecies-wrapper-v4".getBytes(StandardCharsets.UTF_8),
                "AES-GCM-key".getBytes(StandardCharsets.UTF_8),
                32
            );

            // Decrypt symmetricKey using AES-GCM
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(wrapperKeyBytes, "AES"),
                new GCMParameterSpec(128, iv));
            return cipher.doFinal(encSymmetricKey);
        } else {
            throw new UnsupportedOperationException("Asymmetric decryption not supported for algorithm: " + alg);
        }
    }

    private static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        byte[] okm = mac.doFinal();
        return Arrays.copyOf(okm, len);
    }
}
