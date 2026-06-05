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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextPropagatorTest {

    private final TenantContextPropagator propagator = new TenantContextPropagator();

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("wrap captures tenant from calling thread and restores it on executor thread")
    void wrapCapturesTenantFromCallingThreadAndRestoresOnExecutorThread() {
        TenantContext.set("tenant-A");

        AtomicReference<String> seenTenantId = new AtomicReference<>();
        Runnable wrapped = propagator.wrap(() -> seenTenantId.set(TenantContext.get()));

        TenantContext.clear();  // simulate executor thread: no tenant set
        wrapped.run();

        assertThat(seenTenantId.get()).isEqualTo("tenant-A");
    }

    @Test
    @DisplayName("wrap restores executor thread's previous tenant in finally block")
    void wrapRestoresPreviousTenantOnExecutorThreadAfterTask() {
        TenantContext.set("tenant-caller");
        Runnable wrapped = propagator.wrap(() -> {});

        TenantContext.clear();
        TenantContext.set("tenant-executor");  // executor thread has its own tenant

        wrapped.run();

        assertThat(TenantContext.get()).isEqualTo("tenant-executor");
    }

    @Test
    @DisplayName("wrap with null calling-thread tenant clears executor thread's tenant after task")
    void wrapWithNullCallingThreadTenantClearsExecutorTenantAfterTask() {
        // calling thread has no tenant
        Runnable wrapped = propagator.wrap(() -> assertThat(TenantContext.get()).isNull());

        TenantContext.set("executor-tenant");
        wrapped.run();

        // executor's previous tenant restored
        assertThat(TenantContext.get()).isEqualTo("executor-tenant");
    }

    @Test
    @DisplayName("wrap restores tenant even when task throws")
    void wrapRestoresTenantEvenWhenTaskThrows() {
        TenantContext.set("tenant-caller");
        Runnable wrapped = propagator.wrap(() -> { throw new RuntimeException("fail"); });

        TenantContext.clear();
        TenantContext.set("executor-tenant");

        try {
            wrapped.run();
        } catch (RuntimeException ignored) {}

        assertThat(TenantContext.get()).isEqualTo("executor-tenant");
    }

    @Test
    @DisplayName("wrap does not set tenant on executor thread when calling-thread tenant is null")
    void wrapDoesNotSetTenantWhenCallingThreadTenantIsNull() {
        // calling thread: no tenant
        AtomicReference<String> seenTenant = new AtomicReference<>("sentinel");
        Runnable wrapped = propagator.wrap(() -> seenTenant.set(TenantContext.get()));

        TenantContext.clear();
        wrapped.run();

        assertThat(seenTenant.get()).isNull();
    }
}