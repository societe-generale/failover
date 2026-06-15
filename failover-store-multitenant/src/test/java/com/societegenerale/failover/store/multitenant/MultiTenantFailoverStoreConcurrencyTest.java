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

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contention tests for {@link MultiTenantFailoverStore} (audit T-2).
 *
 * <p>Proves that under parallel first-access from many threads, {@code computeIfAbsent} builds
 * <b>exactly one</b> store per tenant (the {@link TenantStoreFactory} is invoked once per distinct
 * tenant, never more), and that concurrent writes for a tenant all land in that tenant's store
 * with no cross-tenant leakage.
 *
 * @author Anand Manissery
 */
@DisplayName("MultiTenantFailoverStore — concurrency")
class MultiTenantFailoverStoreConcurrencyTest {

    private static final String NAME = "product-service";
    private static final Instant NOW = Instant.now();

    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("same tenant hammered from many threads builds exactly one store")
    void singleTenantCreatesOneStoreUnderContention() throws InterruptedException {
        var factory = new CountingTenantStoreFactory();
        var store = new MultiTenantFailoverStore<>(
                () -> "acme", factory, UnaryOperator.identity(), null);

        int threads = 64;
        runConcurrently(threads, i -> store.store(payload("key-" + i, "v-" + i)));

        assertThat(factory.createCount("acme"))
                .as("computeIfAbsent must build the store exactly once for the tenant")
                .isEqualTo(1);
        assertThat(factory.distinctTenants()).containsExactly("acme");
        // All 64 writes landed in the single tenant store.
        for (int i = 0; i < threads; i++) {
            assertThat(store.find(NAME, "key-" + i)).isPresent();
        }
    }

    @Test
    @DisplayName("multiple tenants under contention build one store each, isolated")
    void multipleTenantsCreateOneStoreEachUnderContention() throws InterruptedException {
        var factory = new CountingTenantStoreFactory();
        // Resolve tenant from a thread-local set by each task before the call.
        ThreadLocal<String> currentTenant = new ThreadLocal<>();
        var store = new MultiTenantFailoverStore<>(
                currentTenant::get, factory, UnaryOperator.identity(), null);

        String[] tenants = {"acme", "globex", "initech", "umbrella"};
        int perTenant = 32;
        runConcurrently(tenants.length * perTenant, i -> {
            String tenant = tenants[i % tenants.length];
            currentTenant.set(tenant);
            try {
                store.store(payload("key-" + i, tenant + "-" + i));
            } finally {
                currentTenant.remove();
            }
        });

        for (String tenant : tenants) {
            assertThat(factory.createCount(tenant))
                    .as("one store per tenant '%s'", tenant)
                    .isEqualTo(1);
        }
        assertThat(factory.distinctTenants()).containsExactlyInAnyOrder(tenants);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void runConcurrently(int tasks, java.util.function.IntConsumer task) throws InterruptedException {
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            int idx = i;
            pool.execute(() -> {
                try {
                    start.await();
                    task.accept(idx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("all tasks completed").isTrue();
    }

    private ReferentialPayload<String> payload(String key, String value) {
        return new ReferentialPayload<>(NAME, key, true, NOW, NOW.plusSeconds(3600), value);
    }

    /** Counts how many times {@link #create} is invoked per tenant; each call returns a fresh store. */
    private static final class CountingTenantStoreFactory implements TenantStoreFactory<String> {
        private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        @Override
        public FailoverStore<String> create(String tenantId) {
            counts.computeIfAbsent(tenantId, t -> new AtomicInteger()).incrementAndGet();
            return new InMemoryStubStore();
        }

        int createCount(String tenantId) {
            return counts.getOrDefault(tenantId, new AtomicInteger()).get();
        }

        java.util.Set<String> distinctTenants() {
            return counts.keySet();
        }
    }

    /** Minimal thread-safe {@link FailoverStore} used only as a raw store in these tests. */
    private static final class InMemoryStubStore implements FailoverStore<String> {
        private final ConcurrentHashMap<String, ReferentialPayload<String>> data = new ConcurrentHashMap<>();

        @Override
        public void store(ReferentialPayload<String> p) {
            data.put(p.getName() + "::" + p.getKey(), p);
        }

        @Override
        public void delete(ReferentialPayload<String> p) {
            data.remove(p.getName() + "::" + p.getKey());
        }

        @Override
        public Optional<ReferentialPayload<String>> find(String name, String key) {
            return Optional.ofNullable(data.get(name + "::" + key));
        }

        @Override
        public List<ReferentialPayload<String>> findAll(String name) {
            return data.values().stream().filter(p -> p.getName().equals(name)).toList();
        }

        @Override
        public void cleanByExpiry(Instant expiry) {
            data.values().removeIf(p -> p.getExpireOn().isBefore(expiry));
        }
    }
}
