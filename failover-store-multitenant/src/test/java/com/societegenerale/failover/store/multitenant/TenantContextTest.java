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

package com.societegenerale.failover.store.multitenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("get() returns null when not set")
    void returnsNullWhenNotSet() {
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("get() returns value after set()")
    void returnsValueAfterSet() {
        TenantContext.set("acme");
        assertThat(TenantContext.get()).isEqualTo("acme");
    }

    @Test
    @DisplayName("clear() removes the value")
    void clearRemovesValue() {
        TenantContext.set("acme");
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("set() overwrites existing value")
    void setOverwritesExistingValue() {
        TenantContext.set("acme");
        TenantContext.set("globex");
        assertThat(TenantContext.get()).isEqualTo("globex");
    }

    @Test
    @DisplayName("value is not visible on other threads — ThreadLocal isolation")
    void threadLocalIsolation() throws InterruptedException {
        TenantContext.set("acme");

        AtomicReference<String> valueOnOtherThread = new AtomicReference<>("NOT_SET");
        Thread t = new Thread(() -> valueOnOtherThread.set(TenantContext.get()));
        t.start();
        t.join(2000);

        assertThat(valueOnOtherThread.get())
                .as("ThreadLocal value from calling thread must not leak to other thread")
                .isNull();
        assertThat(TenantContext.get()).isEqualTo("acme");
    }
}