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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Default {@link PayloadCipher}: Base64 encode/decode.
 *
 * <p><b>This is encoding, not encryption.</b> It provides <em>zero</em> confidentiality — anyone who
 * can read the store can trivially decode it. It exists only as a no-dependency default and a worked
 * example of the SPI. For real protection, declare your own {@link PayloadCipher} bean (AES-GCM, a
 * KMS/Jasypt-backed implementation, …). The auto-configuration logs a WARN when this cipher is the
 * active write cipher.
 *
 * @author Anand Manissery
 */
public class Base64PayloadCipher implements PayloadCipher {

    /** Envelope id persisted in {@code ENC(b64:...)}. */
    public static final String ID = "b64";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public @Nullable String encrypt(@Nullable String plaintext) {
        if (plaintext == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public @Nullable String decrypt(@Nullable String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        return new String(Base64.getDecoder().decode(ciphertext), StandardCharsets.UTF_8);
    }
}
