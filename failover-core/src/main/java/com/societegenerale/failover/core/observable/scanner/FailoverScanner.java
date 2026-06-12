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

package com.societegenerale.failover.core.observable.scanner;

import com.societegenerale.failover.annotations.Failover;

import java.util.List;

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
}
