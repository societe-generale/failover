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

/**
 * {@link TenantResolver} that reads the current tenant ID from {@link TenantContext}.
 *
 * <p>This is the canonical resolver for the SCHEMA isolation strategy, where a
 * servlet filter (or equivalent) sets {@link TenantContext#set(String)} at the start
 * of each request and clears it in a {@code finally} block.  The resolver then reads
 * the same value and forwards it to {@link MultiTenantFailoverStore} <em>and</em>
 * serves as the routing key for the application's {@code AbstractRoutingDataSource}.
 *
 * <h2>Example wiring</h2>
 * <pre>{@code
 * // In a servlet filter:
 * String tenantId = request.getHeader("X-Tenant-ID");
 * TenantContext.set(tenantId);
 * try {
 *     filterChain.doFilter(request, response);
 * } finally {
 *     TenantContext.clear();
 * }
 *
 * // TenantResolver bean:
 * @Bean
 * public TenantResolver tenantResolver() {
 *     return new TenantContextTenantResolver();
 * }
 *
 * // AbstractRoutingDataSource:
 * public class TenantRoutingDataSource extends AbstractRoutingDataSource {
 *     @Override
 *     protected Object determineCurrentLookupKey() {
 *         return TenantContext.get();   // same ThreadLocal — no extra wiring
 *     }
 * }
 * }</pre>
 *
 * @author Anand Manissery
 */
public class TenantContextTenantResolver implements TenantResolver {

    @Override
    public String resolve() {
        return TenantContext.get();
    }
}