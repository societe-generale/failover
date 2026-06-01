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

import lombok.RequiredArgsConstructor;

/**
 * {@link TenantResolver} that always returns the same literal tenant ID.
 *
 * <p>Useful for testing (pin a specific tenant) or single-tenant apps migrating to multitenant.
 *
 * @author Anand Manissery
 */
@RequiredArgsConstructor
public class FixedTenantResolver implements TenantResolver {

    private final String tenantId;

    @Override
    public String resolve() {
        return tenantId;
    }
}