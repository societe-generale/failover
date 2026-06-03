/*
 * Copyright 2022-2023, Société Générale All rights reserved.
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

package com.societegenerale.failover.core.key;

import com.societegenerale.failover.annotations.Failover;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Primary {@link KeyGenerator} that orchestrates key generation for a failover operation.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>If {@link com.societegenerale.failover.annotations.Failover#keyGenerator()} is empty,
 *       delegates to the injected {@code defaultKeyGenerator}.</li>
 *   <li>Otherwise, looks up a {@link KeyGenerator} by that name via {@link KeyGeneratorLookup}.
 *       Throws {@link KeyGeneratorNotFoundException} if no match is found (neither by qualifier
 *       nor by bean name).</li>
 * </ol>
 *
 * <p>The final key is a deterministic type-3 UUID derived from
 * {@code failover.name() + ":" + rawKey} encoded as UTF-8 bytes. Hashing normalises key length
 * and prevents store-column overflow regardless of argument size.
 *
 * @author Anand Manissery
 * @see DefaultKeyGenerator
 * @see KeyGeneratorLookup
 */
@AllArgsConstructor
public class FailoverKeyGenerator implements KeyGenerator {

    private final KeyGenerator defaultKeyGenerator;

    private final KeyGeneratorLookup keyGeneratorLookup;

    /**
     * Resolves the appropriate {@link KeyGenerator} and returns the final UUID key.
     *
     * <p>If {@link Failover#keyGenerator()} is empty, delegates to the injected
     * {@code defaultKeyGenerator}. Otherwise looks up the named generator via
     * {@link KeyGeneratorLookup}. In either case the raw key is hashed into a
     * deterministic type-3 UUID prefixed with {@code failover.name()}.
     *
     * @param failover annotation metadata for the intercepted method
     * @param args     resolved method arguments
     * @return deterministic type-3 UUID string uniquely identifying this call
     * @throws KeyGeneratorNotFoundException if {@code failover.keyGenerator()} is non-empty but
     *         no matching bean is found (neither by qualifier nor by bean name)
     */
    @Override
    public String key(Failover failover, List<Object> args) {
        if(failover.keyGenerator().isEmpty()) {
            return generateFinalKey(failover, defaultKeyGenerator.key(failover, args));
        }
        KeyGenerator keyGenerator = keyGeneratorLookup.lookup(failover.keyGenerator());
        if(keyGenerator == null) {
            throw new KeyGeneratorNotFoundException("No matching KeyGenerator bean found for failover '%s' with key generator qualifier '%s'. Neither qualifier match nor bean name match!".formatted(failover.name(), failover.keyGenerator()));
        }
        return generateFinalKey(failover, keyGenerator.key(failover, args));
    }

    private String generateFinalKey(Failover failover, String tempKey) {
        var key = failover.name() + ":" + tempKey;
        return UUID.nameUUIDFromBytes(key.getBytes(UTF_8)).toString();
    }
}
