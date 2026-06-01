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

import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.multitenant.TenantStoreFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Central assembler that creates the single {@link FailoverStore}{@code <Object>} bean by
 * combining a {@link TenantStoreFactory} (raw store) with the standard decorator chain
 * ({@link DefaultFailoverStore} + optionally {@link FailoverStoreAsync}).
 *
 * <p>This configuration runs after all store-type configurations so their
 * {@link TenantStoreFactory} beans are available for injection.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>async (default)</b> — {@code FailoverStoreAsync(DefaultFailoverStore(raw))}.
 *       Write operations are offloaded to {@code failoverTaskExecutor}.</li>
 *   <li><b>sync</b> — {@code DefaultFailoverStore(raw)} only.
 *       Activated via {@code failover.store.async=false}.</li>
 * </ul>
 *
 * @author Anand Manissery
 */
@AutoConfiguration(after = {
        FailoverAutoConfiguration.class,
        FailoverJdbcStoreAutoConfiguration.class,
        FailoverCaffeineStoreAutoConfiguration.class
})
@Slf4j
public class FailoverStoreAutoConfiguration {

    /**
     * Default {@link TaskExecutor} for async store operations.
     *
     * <p>Applications can override by declaring a bean named {@code failoverTaskExecutor}.
     * Uses virtual threads when available (JDK 21+), otherwise platform threads.
     */
    @Bean("failoverTaskExecutor")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(name = "failoverTaskExecutor")
    @ConditionalOnProperty(prefix = "failover.store", name = "async", havingValue = "true", matchIfMissing = true)
    public TaskExecutor failoverTaskExecutor() {
        var executor = new SimpleAsyncTaskExecutor("failover-async-");
        executor.setVirtualThreads(true);
        return executor;
    }

    /**
     * Async store: {@code FailoverStoreAsync(DefaultFailoverStore(raw))}.
     * Active when {@code failover.store.async=true} (default).
     */
    @Bean("failoverStore")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(FailoverStore.class)
    @ConditionalOnProperty(prefix = "failover.store", name = "async", havingValue = "true", matchIfMissing = true)
    public FailoverStore<Object> asyncFailoverStore(
            TenantStoreFactory<Object> storeFactory,
            @Qualifier("failoverTaskExecutor") TaskExecutor failoverTaskExecutor) {
        log.info("FailoverStore assembled: FailoverStoreAsync(DefaultFailoverStore(raw)) — async=true.");
        return new FailoverStoreAsync<>(
                new DefaultFailoverStore<>(storeFactory.create(TenantStoreFactory.SINGLE_TENANT_ID)),
                failoverTaskExecutor);
    }

    /**
     * Sync store: {@code DefaultFailoverStore(raw)}.
     * Active when {@code failover.store.async=false}.
     */
    @Bean("failoverStore")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(FailoverStore.class)
    @ConditionalOnProperty(prefix = "failover.store", name = "async", havingValue = "false")
    public FailoverStore<Object> syncFailoverStore(TenantStoreFactory<Object> storeFactory) {
        log.info("FailoverStore assembled: DefaultFailoverStore(raw) — async=false.");
        return new DefaultFailoverStore<>(storeFactory.create(TenantStoreFactory.SINGLE_TENANT_ID));
    }
}