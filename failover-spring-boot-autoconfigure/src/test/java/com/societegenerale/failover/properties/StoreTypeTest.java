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

class StoreTypeTest {

    @Test
    @DisplayName("should have exactly 4 values: INMEMORY, CAFFEINE, JDBC, CUSTOM")
    void shouldHaveExpectedValues() {
        assertThat(StoreType.values())
                .containsExactly(StoreType.INMEMORY, StoreType.CAFFEINE, StoreType.JDBC, StoreType.CUSTOM);
    }

    @Test
    @DisplayName("INMEMORY resolves by name")
    void inmemoryResolvesFromName() {
        assertThat(StoreType.valueOf("INMEMORY")).isEqualTo(StoreType.INMEMORY);
    }

    @Test
    @DisplayName("CAFFEINE resolves by name")
    void caffeineResolvesFromName() {
        assertThat(StoreType.valueOf("CAFFEINE")).isEqualTo(StoreType.CAFFEINE);
    }

    @Test
    @DisplayName("JDBC resolves by name")
    void jdbcResolvesFromName() {
        assertThat(StoreType.valueOf("JDBC")).isEqualTo(StoreType.JDBC);
    }

    @Test
    @DisplayName("CUSTOM resolves by name")
    void customResolvesFromName() {
        assertThat(StoreType.valueOf("CUSTOM")).isEqualTo(StoreType.CUSTOM);
    }
}
