/*
 * Copyright 2022-2026, Société Générale All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.societegenerale.failover.store.jdbc.serializer.cipher;

import org.jspecify.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Production-grade {@link PayloadCipher} using <b>AES-GCM</b> (authenticated encryption) — the
 * built-in option for real payload-at-rest confidentiality in the JDBC store (audit A4).
 *
 * <h2>Format</h2>
 * <p>Each {@link #encrypt} call generates a fresh random 16-byte IV (nonce) and produces
 * {@code Base64( IV (16 bytes) || ciphertext+GCM-tag )}. A random IV per write means encrypting the
 * same plaintext twice yields different ciphertext (semantic security) and is mandatory for GCM —
 * never reuse an IV with the same key. The 128-bit GCM tag authenticates the ciphertext, so
 * {@link #decrypt} throws (rather than returning garbage) if a stored row was tampered with or was
 * encrypted under a different key.
 *
 * <h2>Key</h2>
 * <p>The key is supplied at construction as raw bytes and must be a valid AES key length: 16, 24, or
 * 32 bytes (AES-128 / 192 / 256). Provide a high-entropy key from a secret manager / KMS / env var —
 * never a hard-coded or password-derived constant. See {@link #fromBase64(String)} for the common
 * Base64-encoded-key path used by the auto-configuration.
 *
 * <h2>Rotation</h2>
 * <p>The {@link EncryptingSerializer} dispatches reads by the {@code ENC(<id>:...)} tag, so rows stay
 * readable across cipher changes as long as the cipher that wrote them is still registered. To rotate
 * the AES key, keep the old key's cipher available for reads while writing with the new one (e.g. via
 * distinct registered ids).
 *
 * @author Anand Manissery
 * @see PayloadCipher
 * @see EncryptingSerializer
 */
public class AesGcmPayloadCipher implements PayloadCipher {

    /** Envelope id persisted in {@code ENC(aesgcm:...)}. */
    private static final String ID = "aesgcm";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 16;      // 128-bit IV (prepended to ciphertext, same length used on decrypt)
    private static final int GCM_TAG_LENGTH_BITS = 128; // full-strength authentication tag

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates an AES-GCM cipher from raw key bytes.
     *
     * @param keyBytes the AES key; must be 16, 24, or 32 bytes long (AES-128/192/256)
     * @throws IllegalArgumentException if the key length is not a valid AES key size
     */
    public AesGcmPayloadCipher(byte[] keyBytes) {
        if (keyBytes == null || (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32)) {
            throw new IllegalArgumentException(
                    "AES-GCM key must be 16, 24, or 32 bytes (AES-128/192/256), but was "
                            + (keyBytes == null ? "null" : keyBytes.length + " bytes")
                            + ". Provide a high-entropy key from a secret manager, Base64-encoded in "
                            + "failover.store.jdbc.encryption.key.");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Creates an AES-GCM cipher from a Base64-encoded key — the form bound from
     * {@code failover.store.jdbc.encryption.key}.
     *
     * @param base64Key the Base64-encoded AES key (decodes to 16, 24, or 32 bytes)
     * @return a cipher using the decoded key
     * @throws IllegalArgumentException if the value is not valid Base64 or decodes to an invalid key length
     */
    public static AesGcmPayloadCipher fromBase64(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("failover.store.jdbc.encryption.key must not be empty when the AES-GCM cipher is used.");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("failover.store.jdbc.encryption.key is not valid Base64.", e);
        }
        return new AesGcmPayloadCipher(decoded);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public @Nullable String encrypt(@Nullable String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encryption of the failover payload failed.", e);
        }
    }

    @Override
    public @Nullable String decrypt(@Nullable String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            if (all.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("ciphertext too short to contain an IV");
            }
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH_BYTES);
            byte[] body = Arrays.copyOfRange(all, IV_LENGTH_BYTES, all.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Tampered row, wrong key, or non-AES-GCM input — fail loudly, never return garbage.
            throw new IllegalStateException(
                    "AES-GCM decryption of the failover payload failed (wrong key, tampered data, or "
                            + "row not written by this cipher).", e);
        }
    }
}
