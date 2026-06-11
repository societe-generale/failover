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

package com.societegenerale.failover.core.expiry;

/**
 * Strategy for resolving an {@link ExpiryPolicy} by qualifier or bean name.
 *
 * @param <T> the payload type the resolved policy operates on
 * @author Anand Manissery
 */
public interface ExpiryPolicyLookup<T> {

    /**
     * Returns the {@link ExpiryPolicy} registered under {@code name}, or {@code null} if not found.
     *
     * @param name qualifier or bean name as declared in {@code @Failover(expiryPolicy = "...")}
     * @return matching policy, or {@code null} if no match exists
     */
    ExpiryPolicy<T> lookup(String name);
}
