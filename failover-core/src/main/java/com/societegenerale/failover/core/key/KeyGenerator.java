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

package com.societegenerale.failover.core.key;

import com.societegenerale.failover.annotations.Failover;

import java.util.List;

/**
 * Strategy interface for generating a cache key from a failover operation's method arguments.
 *
 * <p>Implementations receive the {@link Failover} annotation metadata and the resolved
 * argument list, and must return a stable, non-null string that uniquely identifies the call
 * within the scope of that failover operation.
 *
 * <p>The raw key returned here is further processed by {@link FailoverKeyGenerator}, which
 * prefixes it with {@code failover.name()} and converts the result to a deterministic UUID.
 *
 * @author Anand Manissery
 * @see DefaultKeyGenerator
 * @see FailoverKeyGenerator
 */
public interface KeyGenerator {

    /**
     * Generates a raw cache key for the given failover call.
     *
     * <p><strong>Implementation contract:</strong> Implementations must be <strong>deterministic and side-effect-free</strong>: the same
     * {@code failover} and {@code args} must always yield the same key, and argument values identifying
     * different entities must yield different keys (no collisions). Return a non-null, non-blank value
     * and never mutate {@code args}. A {@code null}/empty {@code args} list is valid (e.g. a no-argument
     * recover-all method) and must still produce a stable key.
     *
     * @param failover annotation metadata for the intercepted method
     * @param args     resolved method arguments; may be {@code null} or empty
     * @return non-null string key representing this call
     */
    String key(Failover failover, List<Object> args);
}
