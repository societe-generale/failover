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

package com.societegenerale.failover.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * JDBC store configuration properties.
 *
 * @author Anand Manissery
 */
@Data
public class Jdbc {

    /**
     * Please provide the prefix for failover store table in case of jdbc storage
     * Default is empty
     */
    private String tablePrefix = "";

    /**
     * Allowlist of payload classes that may be materialized when deserializing rows from the JDBC
     * store. JDBC-only: in-memory/Caffeine hold live objects and never deserialize, so this lives
     * under {@code failover.store.jdbc} rather than the generic store namespace.
     * Entries are exact fully-qualified class names or package prefixes (e.g. {@code "com.acme.referential"}).
     *
     * <p>This is an <em>additive</em> override: the framework already auto-allows the packages of every
     * discovered {@code @Failover} payload type (return type and collection/array element types) so the
     * common case needs no configuration and is secure by default. Add entries here only for payload
     * classes the scanner cannot infer (e.g. a slice type in a different package than its composite).
     *
     * <p>If both this list is empty <em>and</em> the scanner discovers no payload types, the restriction
     * is disabled (allow-all) to preserve backward compatibility.
     */
    private List<String> allowedPayloadClasses = new ArrayList<>();

    /**
     * Payload-at-rest encryption for the JDBC store. JDBC-only: other store types never persist a
     * payload string, so this lives under {@code failover.store.jdbc.encryption} rather than the
     * generic store namespace.
     */
    private Encryption encryption = new Encryption();

    /**
     * Encryption settings for the {@code PAYLOAD} column.
     *
     * <p>{@code enabled} gates the <b>write</b> side only: new rows are encrypted as
     * {@code ENC(<cipher>:<ciphertext>)}. Reads always honour the {@code ENC(...)} marker regardless,
     * so existing encrypted rows stay readable when encryption is toggled off, and plaintext rows
     * stay readable when it is toggled on.
     */
    @Data
    public static class Encryption {

        /**
         * Whether new writes are encrypted. Default {@code false} (write plaintext). Reads decrypt
         * any {@code ENC(...)} row regardless of this flag.
         */
        private boolean enabled = false;

        /**
         * Id of the registered {@code PayloadCipher} to encrypt new writes with. Defaults to
         * {@code "b64"} (the built-in Base64 encoder — encoding only, not real encryption). Declare a
         * {@code PayloadCipher} bean with a real algorithm and set this to its id for actual protection.
         */
        private String cipher = "b64";
    }
}
