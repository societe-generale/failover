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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.MyTestApplication;
import com.societegenerale.failover.aspect.FailoverAspect;
import com.societegenerale.failover.core.BasicFailoverExecution;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreInmemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.societegenerale.failover.configuration.BeanAssertions.assertBasicBean;
import static com.societegenerale.failover.configuration.BeanAssertions.assertBeansAreNotNull;
import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
@SpringBootTest(classes = {MyTestApplication.class})
class FailoverAutoConfigurationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FailoverStore<Object> failoverStore;

    @Test
    @DisplayName("should load all the basic default beans")
    void shouldLoadAllTheBasicDefaultBeans() {
        assertBasicBean(applicationContext);
    }

    @Test
    @DisplayName("should load failover aspect bean")
    void shouldLoadFailoverAspectBean() {
        assertBeansAreNotNull(applicationContext, FailoverAspect.class);
    }

    @Test
    @DisplayName("should load BasicFailoverExecution by default")
    void shouldLoadResilienceFailoverExecutionByDefault() {
        var bean = applicationContext.getBean(BasicFailoverExecution.class);
        assertThat(bean).isNotNull();
    }

    @Test
    @DisplayName("should load inmemory failover store by default")
    void shouldLoadInmemoryFailoverStoreByDefault() throws Exception {
        assertThat(failoverStore).isNotNull();
        Object target = AopUtils.isAopProxy(failoverStore)
                ? ((Advised) failoverStore).getTargetSource().getTarget()
                : failoverStore;
        FailoverStoreAsync<Object> async = cast(target);
        assertThat(async).isNotNull();
        FailoverStore<Object> inner = requireNonNull(async.getFailoverStore());
        assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
        assertThat(requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);
    }

    @Test
    @DisplayName("should wrap async and default stores with the given inmemory failover store")
    @SuppressWarnings("unchecked")
    void shouldWrapAsyncAndDefaultStoresWithTheGivenInmemoryFailoverStore() throws Exception {
        Object target = AopUtils.isAopProxy(failoverStore)
                ? ((Advised) failoverStore).getTargetSource().getTarget()
                : failoverStore;
        assertThat(target).isNotNull();
        assertThat(target).isInstanceOf(FailoverStoreAsync.class);
        FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) target).getFailoverStore());
        assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
        FailoverStore<Object> innermost = requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore());
        assertThat(innermost).isInstanceOf(FailoverStoreInmemory.class);
    }

    @Test
    @DisplayName("should execute store asynchronously on a different thread")
    void storeExecutesOnDifferentThread() throws Exception {
        String callingThread = Thread.currentThread().getName();
        AtomicReference<String> storingThread = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Unwrap: AOP proxy → FailoverStoreAsync → DefaultFailoverStore
        Object target = ((Advised) failoverStore).getTargetSource().getTarget();
        FailoverStoreAsync<Object> async = cast(target);
        assertThat(async).isNotNull();
        DefaultFailoverStore<Object> defaultStore = (DefaultFailoverStore<Object>) requireNonNull(async.getFailoverStore());
        FailoverStore<Object> originalInner = requireNonNull(defaultStore.getFailoverStore());

        // Inject thread-capturing wrapper via reflection
        Field innerField = DefaultFailoverStore.class.getDeclaredField("failoverStore");
        innerField.setAccessible(true);
        innerField.set(defaultStore, new ThreadCapturingStore(originalInner, storingThread, latch));

        try {
            failoverStore.store(new ReferentialPayload<>("async-test", "key1", true,
                    LocalDateTime.now(), LocalDateTime.now().plusHours(1), "payload"));

            assertThat(latch.await(5, TimeUnit.SECONDS)).as("store() did not execute within 5 seconds").isTrue();
            assertThat(storingThread.get())
                    .as("store() must run on a thread different from the calling thread")
                    .isNotEqualTo(callingThread);
        } finally {
            innerField.set(defaultStore, originalInner);
        }
    }

    private record ThreadCapturingStore(
            FailoverStore<Object> delegate,
            AtomicReference<String> threadCapture,
            CountDownLatch latch
    ) implements FailoverStore<Object> {

        @Override
        public void store(ReferentialPayload<Object> payload) {
            threadCapture.set(Thread.currentThread().getName());
            latch.countDown();
            delegate.store(payload);
        }

        @Override
        public void delete(ReferentialPayload<Object> payload) {
            delegate.delete(payload);
        }

        @Override
        public Optional<ReferentialPayload<Object>> find(String name, String key) {
            return delegate.find(name, key);
        }

        @Override
        public void cleanByExpiry(LocalDateTime expiry) {
            delegate.cleanByExpiry(expiry);
        }
    }
}