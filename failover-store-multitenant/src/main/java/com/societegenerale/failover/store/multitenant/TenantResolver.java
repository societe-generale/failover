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

package com.societegenerale.failover.store.multitenant;

import org.jspecify.annotations.Nullable;

/**
 * Strategy for resolving the current tenant identifier from the execution context.
 *
 * <p>Always called on the <b>calling (request) thread</b> — see
 * {@link TenantStoreFactory} for the threading contract.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@code HeaderTenantResolver} — reads an HTTP request header</li>
 *   <li>{@code SecurityContextTenantResolver} — reads {@code Authentication.getName()}</li>
 *   <li>{@link FixedTenantResolver} — always returns the same literal (useful for testing)</li>
 * </ul>
 *
 * @author Anand Manissery
 */
@FunctionalInterface
public interface TenantResolver {

    /**
     * Resolves the current tenant ID.
     *
     * @return the tenant ID, or {@code null} if no tenant can be determined from the current context
     */
    @Nullable
    String resolve();
}