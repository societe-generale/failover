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

package com.societegenerale.failover.store.serializer;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.lang.Class.forName;

/**
 * Jackson-backed {@link Serializer} that converts payloads to/from JSON using a provided
 * {@link ObjectMapper}.
 *
 * <p>All methods are null-safe: a {@code null} input returns {@code null} without delegating
 * to the underlying {@code ObjectMapper}.
 *
 * @author Anand Manissery
 * @see Serializer
 */
@RequiredArgsConstructor
public class JsonSerializer implements Serializer {

    private final ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link ObjectMapper#writeValueAsString}.
     */
    @Override
    public @Nullable <T> String serialize(@Nullable T payload) {
        if(payload == null) {
            return null;
        }
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link ObjectMapper#readValue(String, Class)}.
     */
    @Override
    public @Nullable <T> T deserialize(@Nullable String payload, Class<T> clazz) {
        if (payload == null || clazz == null) {
            return null;
        }
        return objectMapper.readValue(payload, clazz);
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable <T> String toClassName(@Nullable T payload) {
        if(payload == null) {
            return null;
        }
        return payload.getClass().getName();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link ClassNotFoundException} is rethrown as an unchecked exception via {@code @SneakyThrows}.
     */
    @SneakyThrows
    @Override
    public @Nullable <T> Class<T> toClass(@Nullable String className) {
        if(className == null) {
            return null;
        }
        return cast(forName(className));
    }
}
