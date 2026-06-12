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

package com.societegenerale.failover.store.serializer;

import org.jspecify.annotations.Nullable;

/**
 * Strategy for serializing and deserializing business payloads to and from a storable
 * string representation (e.g. JSON), and for resolving a payload's runtime {@link Class}.
 *
 * <p>All methods accept {@code null} and return {@code null} when input is {@code null},
 * so callers do not need null-guards around every invocation.
 *
 * @author Anand Manissery
 * @see JsonSerializer
 */
public interface Serializer {

    /**
     * Serializes {@code payload} to its string representation.
     *
     * @param <T>     the payload type
     * @param payload the object to serialize; {@code null} is allowed
     * @return the serialized string, or {@code null} if {@code payload} is {@code null}
     */
    @Nullable <T> String serialize(@Nullable T payload);

    /**
     * Deserializes {@code payload} back to an instance of {@code clazz}.
     *
     * @param <T>     the target type
     * @param payload the serialized string; {@code null} is allowed
     * @param clazz   the target class; {@code null} is allowed
     * @return the deserialized object, or {@code null} if either argument is {@code null}
     */
    @Nullable <T> T deserialize(@Nullable String payload, Class<T> clazz);

    /**
     * Returns the fully-qualified class name of {@code payload}'s runtime type.
     *
     * @param <T>     the payload type
     * @param payload the object whose class name is needed; {@code null} is allowed
     * @return {@code payload.getClass().getName()}, or {@code null} if {@code payload} is {@code null}
     */
    @Nullable <T> String toClassName(@Nullable T payload);

    /**
     * Resolves a {@link Class} from its fully-qualified name.
     *
     * @param <T>       the expected type
     * @param className the fully-qualified class name; {@code null} is allowed
     * @return the resolved {@link Class}, or {@code null} if {@code className} is {@code null}
     * @throws RuntimeException wrapping {@link ClassNotFoundException} if the class cannot be found
     */
    @Nullable <T> Class<T> toClass(@Nullable String className);
}
