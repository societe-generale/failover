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

/**
 * Strategy for encrypting and decrypting the serialized payload string before it is written to /
 * after it is read from the JDBC failover store. Applies <em>only</em> to the JDBC store — other
 * stores (in-memory, Caffeine) keep live objects and never persist a payload string.
 *
 * <h2>Envelope and dispatch</h2>
 * <p>Implementations deal in <b>raw</b> ciphertext only; they do not add or parse the
 * {@code ENC(...)} envelope. The {@link EncryptingSerializer} owns the envelope, writing each value
 * as {@code ENC(<id>:<ciphertext>)} and, on read, dispatching to the registered cipher whose
 * {@link #id()} matches the tag. This lets a single store hold rows written by different ciphers
 * (e.g. {@code ENC(b64:...)} and {@code ENC(aesgcm:...)}) and still decrypt each correctly — the
 * basis for key/algorithm rotation.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #id()} must be short, stable and <b>unique</b> across all registered ciphers; it is
 *       persisted inside every encrypted row, so changing it strands existing rows.</li>
 *   <li>{@link #encrypt}/{@link #decrypt} must round-trip: {@code decrypt(encrypt(x)).equals(x)}.</li>
 *   <li>A {@code null} input returns {@code null}.</li>
 *   <li>{@code decrypt} must throw (not return garbage) when the input was not produced by this
 *       cipher, so a misconfiguration surfaces loudly rather than corrupting the payload.</li>
 * </ul>
 *
 * <p>Declare a {@code PayloadCipher} bean to provide real encryption (AES-GCM, a KMS/Jasypt-backed
 * implementation, …). The built-in {@link Base64PayloadCipher} is encoding only — not security.
 *
 * @author Anand Manissery
 * @see EncryptingSerializer
 * @see Base64PayloadCipher
 */
public interface PayloadCipher {

    /**
     * Short, stable, unique identifier persisted in the {@code ENC(<id>:...)} envelope and used on
     * read to select this cipher. Example: {@code "b64"}, {@code "aesgcm"}.
     *
     * @return the cipher id; must not be {@code null}, empty, or contain {@code ':'} or {@code ')'}
     */
    String id();

    /**
     * Encrypts a serialized payload string to raw ciphertext (no envelope).
     *
     * @param plaintext the serialized payload; {@code null} is allowed
     * @return the raw ciphertext, or {@code null} if {@code plaintext} is {@code null}
     */
    @Nullable String encrypt(@Nullable String plaintext);

    /**
     * Decrypts raw ciphertext (no envelope) back to the serialized payload string.
     *
     * @param ciphertext the raw ciphertext previously returned by {@link #encrypt}; {@code null} is allowed
     * @return the serialized payload, or {@code null} if {@code ciphertext} is {@code null}
     */
    @Nullable String decrypt(@Nullable String ciphertext);
}
