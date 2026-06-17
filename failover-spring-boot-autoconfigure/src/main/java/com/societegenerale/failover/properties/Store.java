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

package com.societegenerale.failover.properties;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import static com.societegenerale.failover.properties.StoreType.INMEMORY;

/**
 * Configuration properties for {@code failover.store.*}.
 *
 * <p>Controls which backing store implementation is used, whether writes are
 * asynchronous, JDBC-specific settings (table prefix), and multi-tenant settings.
 * The default store type is {@link StoreType#INMEMORY} — do not use in production.
 *
 * @author Anand Manissery
 */
@Data
public class Store {

    /**
     * Type of backing store. Default: {@link StoreType#INMEMORY} (not suitable for production).
     * Available options: {@code INMEMORY}, {@code CAFFEINE}, {@code JDBC}, {@code CUSTOM}.
     */
    private StoreType type = INMEMORY;

    /**
     * Whether write operations ({@code store}, {@code delete}, {@code cleanByExpiry}) are offloaded
     * to a background {@code TaskExecutor}. {@code find} is always synchronous.
     * Default: {@code true} (non-blocking writes). Set to {@code false} for synchronous mode,
     * which is required when using the JDBC SCHEMA multi-tenant strategy.
     */
    private boolean async = true;

    @NestedConfigurationProperty
    private Inmemory inmemory = new Inmemory();

    @NestedConfigurationProperty
    private Caffeine caffeine = new Caffeine();

    @NestedConfigurationProperty
    private Jdbc jdbc = new Jdbc();

    @NestedConfigurationProperty
    private MultiTenant multitenant = new MultiTenant();
}
