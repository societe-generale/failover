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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoreTest {

    private final Store store = new Store();

    @Test
    @DisplayName("type defaults to INMEMORY")
    void typeDefaultsToInmemory() {
        assertThat(store.getType()).isEqualTo(StoreType.INMEMORY);
    }

    @Test
    @DisplayName("async defaults to true")
    void asyncDefaultsToTrue() {
        assertThat(store.isAsync()).isTrue();
    }

    @Test
    @DisplayName("jdbc is initialized with empty tablePrefix by default")
    void jdbcInitializedWithDefaults() {
        assertThat(store.getJdbc()).isNotNull();
        assertThat(store.getJdbc().getTablePrefix()).isEmpty();
    }

    @Test
    @DisplayName("multitenant is initialized with disabled flag by default")
    void multitenantInitializedWithDefaults() {
        assertThat(store.getMultitenant()).isNotNull();
        assertThat(store.getMultitenant().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("type can be changed to JDBC")
    void typeCanBeChanged() {
        store.setType(StoreType.JDBC);
        assertThat(store.getType()).isEqualTo(StoreType.JDBC);
    }

    @Test
    @DisplayName("async can be disabled")
    void asyncCanBeDisabled() {
        store.setAsync(false);
        assertThat(store.isAsync()).isFalse();
    }

    @Test
    @DisplayName("asyncExecutor defaults to unbounded (limit 0) with DISCARD rejection policy")
    void asyncExecutorDefaults() {
        assertThat(store.getAsyncExecutor()).isNotNull();
        assertThat(store.getAsyncExecutor().getConcurrencyLimit()).isZero();
        assertThat(store.getAsyncExecutor().getRejectionPolicy())
                .isEqualTo(com.societegenerale.failover.store.async.RejectionPolicy.DISCARD);
    }
}
