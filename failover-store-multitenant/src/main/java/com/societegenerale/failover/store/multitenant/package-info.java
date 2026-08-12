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

/**
 * Multi-tenant failover store with per-tenant routing.
 *
 * <p>{@link com.societegenerale.failover.store.multitenant.MultiTenantFailoverStore} delegates
 * all store operations to a tenant-specific {@code FailoverStore} resolved at runtime by
 * {@link com.societegenerale.failover.store.multitenant.TenantResolver}.
 *
 * <p>{@link com.societegenerale.failover.store.multitenant.TenantContext} holds the current
 * tenant ID in a {@code ThreadLocal}.
 * {@link com.societegenerale.failover.store.multitenant.TenantContextPropagator} propagates
 * it across executor threads for scatter/gather operations.
 */
package com.societegenerale.failover.store.multitenant;
