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

package com.societegenerale.failover.core.scanner;

import com.societegenerale.failover.annotations.Failover;

import java.util.List;
import java.util.Set;

/**
 * Scans the application classpath for methods annotated with {@link Failover}
 * and provides lookup by name.
 *
 * @author Anand Manissery
 */
public interface FailoverScanner {

    /**
     * Returns the {@link Failover} annotation with the given name, or {@code null} if not found.
     *
     * @param name the value of {@link Failover#name()}
     * @return the matching annotation, or {@code null}
     */
    Failover findFailoverByName(String name);

    /**
     * Returns all {@link Failover} annotations discovered during scanning.
     *
     * @return list of all failover annotations; never {@code null}
     */
    List<Failover> findAllFailover();

    /**
     * Returns the payload types discovered on {@code @Failover} methods: each method's return type,
     * or — for a method returning a {@link java.util.Collection} or array — its element/component type.
     *
     * <p>Used to build a secure-by-default deserialization allowlist for serializing stores (the
     * packages of these types are trusted). Returns an empty set by default for implementations that
     * do not track types.
     *
     * @return discovered payload types; never {@code null}
     */
    default Set<Class<?>> findAllPayloadTypes() {
        return Set.of();
    }
}
