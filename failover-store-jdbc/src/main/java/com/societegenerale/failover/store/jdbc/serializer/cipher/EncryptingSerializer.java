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

import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Serializer} decorator that encrypts the serialized payload string before it is stored and
 * decrypts it on read (audit: payload-at-rest encryption for the JDBC store).
 *
 * <h2>Envelope</h2>
 * <p>An encrypted value is wrapped as {@code ENC(<id>:<ciphertext>)} where {@code id} is the
 * {@link PayloadCipher#id()} that produced it. The envelope makes every value self-describing:
 * <ul>
 *   <li><b>read</b> — a value matching {@code ENC(id:..)} is decrypted by the registered cipher with
 *       that {@code id}; any other value is treated as legacy <b>plaintext</b> and passed through.
 *       So a single store may hold a mix of plaintext, {@code ENC(b64:..)} and {@code ENC(aesgcm:..)}
 *       rows and each is read correctly — enabling key/algorithm rotation.</li>
 *   <li><b>write</b> — when an active write cipher is configured, values are encrypted and wrapped;
 *       when it is {@code null} (encryption disabled), values are written as plaintext. Reads still
 *       honour the {@code ENC} marker either way, so toggling encryption never breaks existing rows.</li>
 * </ul>
 *
 * <p>Only {@link #serialize}/{@link #deserialize} (the {@code PAYLOAD} column) are transformed.
 * {@link #toClassName}/{@link #toClass} pass through unchanged, so {@code PAYLOAD_CLASS} stays
 * plaintext and the deserialization allowlist keeps gating on the real class name.
 *
 * @author Anand Manissery
 */
@Slf4j
public class EncryptingSerializer implements Serializer {

    private static final String PREFIX = "ENC(";
    private static final String SUFFIX = ")";

    private final Serializer delegate;

    /** Ciphers available for <b>read</b>, keyed by {@link PayloadCipher#id()}. */
    private final Map<String, PayloadCipher> ciphersById;

    /** Cipher used for <b>write</b>; {@code null} means encryption is disabled (write plaintext). */
    @Nullable
    private final PayloadCipher writeCipher;

    /**
     * @param delegate    the underlying serializer (e.g. {@code JsonSerializer})
     * @param ciphers     all available ciphers (used for reads); ids must be unique and envelope-safe
     * @param writeCipher the cipher to encrypt new writes with, or {@code null} to write plaintext;
     *                    when non-null it must be one of {@code ciphers}
     * @throws IllegalArgumentException if two ciphers share an id, an id is blank or contains
     *                                  {@code ':'}/{@code ')'}, or {@code writeCipher} is not in {@code ciphers}
     */
    public EncryptingSerializer(Serializer delegate, List<PayloadCipher> ciphers, @Nullable PayloadCipher writeCipher) {
        this.delegate = delegate;
        this.ciphersById = indexById(ciphers);
        this.writeCipher = writeCipher;
        if (writeCipher != null && this.ciphersById.get(writeCipher.id()) != writeCipher) {
            throw new IllegalArgumentException("Write cipher '" + writeCipher.id() + "' is not among the registered ciphers " + this.ciphersById.keySet());
        }
    }

    private static Map<String, PayloadCipher> indexById(List<PayloadCipher> ciphers) {
        Map<String, PayloadCipher> map = new LinkedHashMap<>();
        for (PayloadCipher cipher : ciphers) {
            String id = cipher.id();
            if (id == null || id.isBlank() || id.contains(":") || id.contains(")")) {
                throw new IllegalArgumentException("Invalid PayloadCipher id '" + id + "': must be non-blank and contain neither ':' nor ')'.");
            }
            PayloadCipher existing = map.putIfAbsent(id, cipher);
            if (existing != null) {
                throw new IllegalArgumentException("Duplicate PayloadCipher id '" + id + "' (" + existing.getClass().getName() + " and " + cipher.getClass().getName() + "). Ids must be unique.");
            }
        }
        return map;
    }

    @Override
    public @Nullable <T> String serialize(@Nullable T payload) {
        String json = delegate.serialize(payload);
        if (json == null || writeCipher == null) {
            return json; // null payload, or encryption disabled -> plaintext
        }
        return PREFIX + writeCipher.id() + ":" + writeCipher.encrypt(json) + SUFFIX;
    }

    @Override
    public @Nullable <T> T deserialize(@Nullable String payload, Class<T> clazz) {
        return delegate.deserialize(decryptIfEnveloped(payload), clazz);
    }

    /** Strips and decrypts an {@code ENC(id:..)} value via the matching cipher; passes plaintext through. */
    private @Nullable String decryptIfEnveloped(@Nullable String stored) {
        if (stored == null || !stored.startsWith(PREFIX) || !stored.endsWith(SUFFIX)) {
            return stored; // legacy plaintext (or null) — not enveloped
        }
        String body = stored.substring(PREFIX.length(), stored.length() - SUFFIX.length());
        int sep = body.indexOf(':');
        if (sep < 0) {
            throw new FailoverStoreException("Malformed encrypted payload envelope (missing ':' after cipher id): " + truncate(stored));
        }
        String id = body.substring(0, sep);
        String ciphertext = body.substring(sep + 1);
        PayloadCipher cipher = ciphersById.get(id);
        if (cipher == null) {
            throw new FailoverStoreException("No PayloadCipher registered for id '" + id + "' (available: " + ciphersById.keySet()
                    + "). The row was encrypted by a cipher that is no longer on the classpath; register it to read the row, or let the row expire from the cache.");
        }
        try {
            return cipher.decrypt(ciphertext);
        } catch (Exception e) {
            throw new FailoverStoreException("Failed to decrypt payload with cipher '" + id + "'. The key may be wrong or the data corrupted.", e);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 64 ? s : s.substring(0, 64) + "…";
    }

    @Override
    public @Nullable <T> String toClassName(@Nullable T payload) {
        return delegate.toClassName(payload); // PAYLOAD_CLASS is never encrypted
    }

    @Override
    public @Nullable <T> Class<T> toClass(@Nullable String className) {
        return delegate.toClass(className); // allowlist gating stays on the plaintext class name
    }
}
