package io.github.cyfko.veridot.core.impl;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
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
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
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

            // Derive wrapper key using SHA-256
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] wrapperKeyBytes = md.digest(sharedSecret);

            // Encrypt symmetricKey with wrapperKey
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(wrapperKeyBytes, "AES"));
            byte[] encSymmetricKey = cipher.doFinal(symmetricKey);

            // Structure: [ephemeralPubKeyLen (2 bytes)] + [ephemeralPubKey (variable)] + [encSymmetricKey (variable)]
            byte[] pubBytes = ephemeral.getPublic().getEncoded();
            byte[] result = new byte[2 + pubBytes.length + encSymmetricKey.length];
            result[0] = (byte) ((pubBytes.length >> 8) & 0xFF);
            result[1] = (byte) (pubBytes.length & 0xFF);
            System.arraycopy(pubBytes, 0, result, 2, pubBytes.length);
            System.arraycopy(encSymmetricKey, 0, result, 2 + pubBytes.length, encSymmetricKey.length);
            return result;
        } else {
            throw new UnsupportedOperationException("Asymmetric encryption not supported for algorithm: " + alg);
        }
    }

    public static byte[] decryptAsymmetric(byte[] encryptedKey, PrivateKey privateKey) throws Exception {
        String alg = privateKey.getAlgorithm();
        if ("RSA".equalsIgnoreCase(alg)) {
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(encryptedKey);
        } else if ("EC".equalsIgnoreCase(alg)) {
            if (encryptedKey.length < 2) {
                throw new IllegalArgumentException("Invalid encryptedKey length for EC");
            }
            int pubLen = ((encryptedKey[0] & 0xFF) << 8) | (encryptedKey[1] & 0xFF);
            if (2 + pubLen > encryptedKey.length) {
                throw new IllegalArgumentException("Invalid ephemeral public key length");
            }
            byte[] pubBytes = Arrays.copyOfRange(encryptedKey, 2, 2 + pubLen);
            byte[] encSymmetricKey = Arrays.copyOfRange(encryptedKey, 2 + pubLen, encryptedKey.length);

            // Reconstruct ephemeral public key
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey ephemeralPubKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

            // ECDH
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(privateKey);
            ka.doPhase(ephemeralPubKey, true);
            byte[] sharedSecret = ka.generateSecret();

            // Derive wrapper key
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] wrapperKeyBytes = md.digest(sharedSecret);

            // Decrypt symmetricKey
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrapperKeyBytes, "AES"));
            return cipher.doFinal(encSymmetricKey);
        } else {
            throw new UnsupportedOperationException("Asymmetric decryption not supported for algorithm: " + alg);
        }
    }
}
