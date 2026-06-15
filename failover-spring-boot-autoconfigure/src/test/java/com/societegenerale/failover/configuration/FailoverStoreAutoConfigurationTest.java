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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.async.FailoverStoreAsync;
import com.societegenerale.failover.store.caffeine.FailoverStoreCaffeine;
import com.societegenerale.failover.store.inmemory.FailoverStoreInmemory;
import com.societegenerale.failover.store.jdbc.FailoverStoreJdbc;
import com.societegenerale.failover.store.multitenant.FixedTenantResolver;
import com.societegenerale.failover.store.multitenant.MultiTenantFailoverStore;
import com.societegenerale.failover.store.multitenant.TenantResolver;
import com.societegenerale.failover.store.multitenant.TenantStoreFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.TestPropertySource;

import static com.societegenerale.failover.configuration.BeanAssertions.assertBasicBean;
import java.util.Map;
import java.util.Set;

import org.springframework.test.util.ReflectionTestUtils;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

class FailoverStoreAutoConfigurationTest {

    // ── Single-tenant ───────────────────────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.store.async=true"})
    @DisplayName("when failover.store.async=true")
    class WhenStoreAsyncEnabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("should register failoverTaskExecutor bean")
        void shouldRegisterTaskExecutorBean() {
            assertThat(applicationContext.getBeansOfType(TaskExecutor.class)).containsKey("failoverTaskExecutor");
        }

        @Test
        @DisplayName("should register TenantStoreFactory bean")
        void shouldRegisterTenantStoreFactory() {
            assertThat(applicationContext.getBeansOfType(TenantStoreFactory.class)).isNotEmpty();
        }

        @Test
        @DisplayName("failoverStore should be FailoverStoreAsync wrapping DefaultFailoverStore")
        void shouldWrapStoreAsAsyncAroundDefault() {
            assertThat(failoverStore).isInstanceOf(FailoverStoreAsync.class);
            FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) failoverStore).getFailoverStore());
            assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
        }

        @Test
        @DisplayName("innermost store should be FailoverStoreInmemory by default")
        void innermostShouldBeInmemory() {
            FailoverStoreAsync<Object> async = cast(failoverStore);
            DefaultFailoverStore<Object> defaultStore = cast(requireNonNull(async.getFailoverStore()));
            assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.store.async=false"})
    @DisplayName("when failover.store.async=false")
    class WhenStoreAsyncDisabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("should NOT register failoverTaskExecutor bean")
        void shouldNotRegisterTaskExecutorBean() {
            assertThat(applicationContext.getBeansOfType(TaskExecutor.class))
                    .doesNotContainKey("failoverTaskExecutor");
        }

        @Test
        @DisplayName("failoverStore should be DefaultFailoverStore only — no async wrapping")
        void shouldWrapStoreAsDefaultOnly() {
            assertThat(failoverStore)
                    .isInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(FailoverStoreAsync.class);
        }

        @Test
        @DisplayName("innermost store should be FailoverStoreInmemory")
        void innermostShouldBeInmemory() {
            DefaultFailoverStore<Object> defaultStore = cast(failoverStore);
            assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);
        }
    }

    // ── @TestConfiguration ────────────────────────────────────────────────────

    @TestConfiguration
    static class CustomStoreFactoryConfig {
        @Bean
        public TenantStoreFactory<Object> tenantStoreFactory() {
            return tenantId -> new FailoverStoreInmemory<>();
        }
    }

    @TestConfiguration
    static class CustomTaskExecutorConfig {
        @Bean("failoverTaskExecutor")
        public TaskExecutor failoverTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    // ── Multi-tenant ────────────────────────────────────────────────────────────

    @TestConfiguration
    static class MultiTenantTestConfig {
        @Bean
        public TenantResolver tenantResolver() {
            return new FixedTenantResolver("test-tenant");
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, MultiTenantTestConfig.class})
    @TestPropertySource(properties = {
            "failover.store.multitenant.enabled=true",
            "failover.store.async=true"
    })
    @DisplayName("when multitenant enabled and async=true")
    class WhenMultiTenantAsyncEnabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("failoverStore should be MultiTenantFailoverStore")
        void failoverStoreShouldBeMultiTenantFailoverStore() {
            assertThat(failoverStore).isInstanceOf(MultiTenantFailoverStore.class);
        }

        @Test
        @DisplayName("per-tenant chain is MultiTenantFailoverStore → FailoverStoreAsync → DefaultFailoverStore → raw")
        @SuppressWarnings("unchecked")
        void perTenantChainIsAsyncWrappingDefault() {
            MultiTenantFailoverStore<Object> multiTenant = cast(failoverStore);
            multiTenant.prewarm(Set.of("test-tenant"));
            Map<String, FailoverStore<Object>> stores =
                    (Map<String, FailoverStore<Object>>) ReflectionTestUtils.getField(multiTenant, "stores");
            FailoverStore<Object> tenantStore = requireNonNull(stores).get("test-tenant");
            assertThat(tenantStore).isInstanceOf(FailoverStoreAsync.class);
            FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) tenantStore).getFailoverStore());
            assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
            assertThat(requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore()))
                    .isNotInstanceOf(FailoverStoreAsync.class)
                    .isNotInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(MultiTenantFailoverStore.class);
        }

        @Test
        @DisplayName("should register failoverTaskExecutor bean")
        void shouldRegisterTaskExecutorBean() {
            assertThat(applicationContext.getBeansOfType(TaskExecutor.class)).containsKey("failoverTaskExecutor");
        }

        @Test
        @DisplayName("should register exactly one FailoverStore bean")
        void shouldRegisterExactlyOneFailoverStore() {
            assertThat(applicationContext.getBeansOfType(FailoverStore.class)).hasSize(1);
        }

        @Test
        @DisplayName("single-tenant factory should NOT be registered — multitenant factory used instead")
        void singleTenantFactoryNotRegistered() {
            // only one TenantStoreFactory — the multitenant one from FailoverMultiTenantAutoConfiguration
            assertThat(applicationContext.getBeansOfType(TenantStoreFactory.class)).hasSize(1);
        }
    }

    // ── Store types ──────────────────────────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.store.type=caffeine"})
    @DisplayName("when store type is caffeine")
    class WhenStoreCaffeine {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("should load all basic default beans")
        void shouldLoadAllBasicDefaultBeans() {
            assertBasicBean(applicationContext);
        }

        @Test
        @DisplayName("failoverStore is FailoverStoreAsync(DefaultFailoverStore(FailoverStoreCaffeine))")
        void shouldLoadCaffeineFailoverStoreBean() {
            assertThat(failoverStore).isInstanceOf(FailoverStoreAsync.class);
            FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) failoverStore).getFailoverStore());
            assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
            assertThat(requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore())).isInstanceOf(FailoverStoreCaffeine.class);
        }

        @Test
        @DisplayName("innermost store is FailoverStoreCaffeine")
        void innermostShouldBeCaffeine() {
            FailoverStoreAsync<Object> async = cast(failoverStore);
            DefaultFailoverStore<Object> defaultStore = cast(requireNonNull(async.getFailoverStore()));
            assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreCaffeine.class);
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.store.type=jdbc", "failover.store.jdbc.table-prefix=DEAL_"})
    @DisplayName("when store type is jdbc")
    class WhenStoreJdbc {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("should load all basic default beans")
        void shouldLoadAllBasicDefaultBeans() {
            assertBasicBean(applicationContext);
        }

        @Test
        @DisplayName("failoverStore is FailoverStoreAsync(DefaultFailoverStore(FailoverStoreJdbc))")
        void shouldLoadJdbcFailoverStoreBean() {
            assertThat(failoverStore).isInstanceOf(FailoverStoreAsync.class);
            FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) failoverStore).getFailoverStore());
            assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
            assertThat(requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore())).isInstanceOf(FailoverStoreJdbc.class);
        }

        @Test
        @DisplayName("innermost store is FailoverStoreJdbc")
        void innermostShouldBeJdbc() {
            FailoverStoreAsync<Object> async = cast(failoverStore);
            DefaultFailoverStore<Object> defaultStore = cast(requireNonNull(async.getFailoverStore()));
            assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreJdbc.class);
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, MultiTenantTestConfig.class})
    @TestPropertySource(properties = {
            "failover.store.multitenant.enabled=true",
            "failover.store.async=false"
    })
    @DisplayName("when multitenant enabled and async=false")
    class WhenMultiTenantAsyncDisabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("failoverStore should be MultiTenantFailoverStore")
        void failoverStoreShouldBeMultiTenantFailoverStore() {
            assertThat(failoverStore).isInstanceOf(MultiTenantFailoverStore.class);
        }

        @Test
        @DisplayName("per-tenant chain is MultiTenantFailoverStore → DefaultFailoverStore → raw (no async)")
        @SuppressWarnings("unchecked")
        void perTenantChainIsDefaultWrappingRawNoAsync() {
            MultiTenantFailoverStore<Object> multiTenant = cast(failoverStore);
            multiTenant.prewarm(Set.of("test-tenant"));
            Map<String, FailoverStore<Object>> stores =
                    (Map<String, FailoverStore<Object>>) ReflectionTestUtils.getField(multiTenant, "stores");
            FailoverStore<Object> tenantStore = requireNonNull(stores).get("test-tenant");
            assertThat(tenantStore)
                    .isInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(FailoverStoreAsync.class);
            assertThat(requireNonNull(((DefaultFailoverStore<Object>) tenantStore).getFailoverStore()))
                    .isNotInstanceOf(FailoverStoreAsync.class)
                    .isNotInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(MultiTenantFailoverStore.class);
        }

        @Test
        @DisplayName("should NOT register failoverTaskExecutor bean")
        void shouldNotRegisterTaskExecutorBean() {
            assertThat(applicationContext.getBeansOfType(TaskExecutor.class))
                    .doesNotContainKey("failoverTaskExecutor");
        }

        @Test
        @DisplayName("should register exactly one FailoverStore bean")
        void shouldRegisterExactlyOneFailoverStore() {
            assertThat(applicationContext.getBeansOfType(FailoverStore.class)).hasSize(1);
        }
    }

    // ── Store type × async combinations ──────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.store.type=caffeine", "failover.store.async=false"})
    @DisplayName("when store type is caffeine and async=false")
    class WhenStoreCaffeineAsyncDisabled {

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("failoverStore should be DefaultFailoverStore only — no async wrapping")
        void shouldBeDefaultFailoverStoreWithoutAsync() {
            assertThat(failoverStore)
                    .isInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(FailoverStoreAsync.class);
        }

        @Test
        @DisplayName("innermost store should be FailoverStoreCaffeine")
        void innermostShouldBeCaffeine() {
            DefaultFailoverStore<Object> defaultStore = cast(failoverStore);
            assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreCaffeine.class);
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.store.type=jdbc", "failover.store.jdbc.table-prefix=DEAL_", "failover.store.async=false"})
    @DisplayName("when store type is jdbc and async=false")
    class WhenStoreJdbcAsyncDisabled {

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("failoverStore should be DefaultFailoverStore only — no async wrapping")
        void shouldBeDefaultFailoverStoreWithoutAsync() {
            assertThat(failoverStore)
                    .isInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(FailoverStoreAsync.class);
        }

        @Test
        @DisplayName("innermost store should be FailoverStoreJdbc")
        void innermostShouldBeJdbc() {
            DefaultFailoverStore<Object> defaultStore = cast(failoverStore);
            assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreJdbc.class);
        }
    }

    // ── Custom overrides (ConditionalOnMissingBean) ───────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, CustomStoreFactoryConfig.class})
    @TestPropertySource(properties = {"failover.store.type=custom"})
    @DisplayName("when store type is custom (user-provided TenantStoreFactory)")
    class WhenStoreTypeCustom {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("should load all basic default beans")
        void shouldLoadAllBasicDefaultBeans() {
            assertBasicBean(applicationContext);
        }

        @Test
        @DisplayName("exactly one TenantStoreFactory — the custom one")
        void exactlyOneTenantStoreFactoryRegistered() {
            assertThat(applicationContext.getBeansOfType(TenantStoreFactory.class)).hasSize(1);
        }

        @Test
        @DisplayName("FailoverStore assembled from custom TenantStoreFactory via async decorator chain")
        void failoverStoreAssembledFromCustomFactory() {
            assertThat(failoverStore).isInstanceOf(FailoverStoreAsync.class);
            FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) failoverStore).getFailoverStore());
            assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
            assertThat(requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, CustomTaskExecutorConfig.class})
    @TestPropertySource(properties = {"failover.store.async=true"})
    @DisplayName("when custom failoverTaskExecutor bean provided")
    class WhenCustomTaskExecutor {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("custom failoverTaskExecutor used — library default backed off via @ConditionalOnMissingBean")
        void customTaskExecutorUsed() {
            assertThat(applicationContext.getBean("failoverTaskExecutor", TaskExecutor.class))
                    .isInstanceOf(SyncTaskExecutor.class);
        }
    }

    @Nested
    @DisplayName("mergeAllowedPayloadClasses — auto-derived scanner packages + configured entries")
    class MergeAllowedPayloadClasses {

        @Test
        @DisplayName("adds the packages of scanner-discovered payload types to the configured entries")
        void mergesScannerPackagesWithConfigured() {
            FailoverScanner scanner = org.mockito.Mockito.mock(FailoverScanner.class);
            doReturn(Set.of(SampleType.class)).when(scanner).findAllPayloadTypes();

            var merged = FailoverStoreAutoConfiguration.mergeAllowedPayloadClasses(
                    java.util.List.of("com.acme.extra"), scanner);

            assertThat(merged).contains("com.acme.extra", SampleType.class.getPackageName());
        }

        @Test
        @DisplayName("never whitelists java.* packages from scanner types")
        void skipsJavaPackages() {
            FailoverScanner scanner = org.mockito.Mockito.mock(FailoverScanner.class);
            doReturn(Set.of(String.class)).when(scanner).findAllPayloadTypes();

            var merged = FailoverStoreAutoConfiguration.mergeAllowedPayloadClasses(java.util.List.of(), scanner);

            assertThat(merged).isEmpty();
        }

        @Test
        @DisplayName("never whitelists javax.* or jakarta.* packages from scanner types")
        void skipsJavaxAndJakartaPackages() {
            FailoverScanner scanner = org.mockito.Mockito.mock(FailoverScanner.class);
            doReturn(Set.of(
                    javax.sql.DataSource.class,                  // javax.sql
                    jakarta.annotation.PostConstruct.class,      // jakarta.annotation
                    SampleType.class))                           // a real app package — kept
                .when(scanner).findAllPayloadTypes();

            var merged = FailoverStoreAutoConfiguration.mergeAllowedPayloadClasses(java.util.List.of(), scanner);

            assertThat(merged)
                    .containsExactly(SampleType.class.getPackageName())
                    .doesNotContain("javax.sql", "jakarta.annotation");
        }

        @Test
        @DisplayName("returns configured entries unchanged when no scanner is available")
        void noScannerKeepsConfigured() {
            var merged = FailoverStoreAutoConfiguration.mergeAllowedPayloadClasses(
                    java.util.List.of("com.acme"), null);
            assertThat(merged).containsExactly("com.acme");
        }

        @Test
        @DisplayName("skips a default-package payload type (empty package name) without adding an empty prefix")
        void skipsDefaultPackageType() throws ClassNotFoundException {
            Class<?> defaultPackageType = Class.forName("DefaultPackagePayloadType");
            assertThat(defaultPackageType.getPackageName()).isEmpty();   // sanity: default (unnamed) package
            FailoverScanner scanner = org.mockito.Mockito.mock(FailoverScanner.class);
            org.mockito.Mockito.doReturn(Set.of(defaultPackageType)).when(scanner).findAllPayloadTypes();

            var merged = FailoverStoreAutoConfiguration.mergeAllowedPayloadClasses(java.util.List.of(), scanner);

            assertThat(merged).isEmpty();
        }
    }

    static class SampleType { }
}