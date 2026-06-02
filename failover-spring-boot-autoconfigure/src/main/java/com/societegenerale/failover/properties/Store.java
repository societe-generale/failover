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

package com.societegenerale.failover.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import static com.societegenerale.failover.properties.StoreType.INMEMORY;

/// Configuration properties for `failover.store.*`.
///
/// Controls which backing store implementation is used, whether writes are
/// asynchronous, JDBC-specific settings (table prefix), and multi-tenant settings.
///
/// The default store type is `INMEMORY`. Do **not** use `INMEMORY` in production.
///
/// @author Anand Manissery
@Getter
@Setter
public class Store {

    /// Type of storage. Default: `INMEMORY` (not suitable for production).
    /// Available options: `INMEMORY`, `CAFFEINE`, `JDBC`, `CUSTOM`.
    private StoreType type = INMEMORY;

    /// Whether write operations (`store`, `delete`, `cleanByExpiry`) are offloaded to a
    /// background `TaskExecutor`. `find` is always synchronous.
    /// Default: `true` (non-blocking writes). Set to `false` for synchronous mode,
    /// which is required when using the JDBC SCHEMA multi-tenant strategy.
    private boolean async = true;

    @NestedConfigurationProperty
    private Jdbc jdbc = new Jdbc();

    @NestedConfigurationProperty
    private MultiTenant multitenant = new MultiTenant();
}