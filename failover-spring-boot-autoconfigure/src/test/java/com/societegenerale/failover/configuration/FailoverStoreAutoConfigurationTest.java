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
import com.societegenerale.failover.store.async.BoundedTaskExecutor;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import static com.societegenerale.failover.configuration.BeanAssertions.assertBasicBean;
import java.util.Map;
import java.util.Set;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

class FailoverStoreAutoConfigurationTest {

    private static final ApplicationContextRunner BASE_RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FailoverAutoConfiguration.class,
                    FailoverStoreAutoConfiguration.class,
                    FailoverStoreMultiTenantAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    FailoverMicrometerAutoConfiguration.class,
                    ResilienceFailoverExecutionAutoConfiguration.class
            ));

    // Extends base with embedded H2 + JdbcTemplate + ObjectMapper for JDBC store tests.
    private static final ApplicationContextRunner JDBC_BASE_RUNNER = BASE_RUNNER
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class
            ))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    // ── @TestConfiguration helpers ────────────────────────────────────────────

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

    @TestConfiguration
    static class MultiTenantTestConfig {
        @Bean
        public TenantResolver tenantResolver() {
            return new FixedTenantResolver("test-tenant");
        }
    }

    // ── Single-tenant — async=true ────────────────────────────────────────────

    @Nested
    @DisplayName("when failover.store.async=true")
    class WhenStoreAsyncEnabled {

        @Test
        @DisplayName("task executor unbounded; store chain is Async→Default→Inmemory; single bean named failoverStore")
        void storeChainAndExecutorWithAsync() {
            BASE_RUNNER.withPropertyValues("failover.store.async=true").run(ctx -> {
                assertThat(ctx.getBeansOfType(TaskExecutor.class)).containsKey("failoverTaskExecutor");
                assertThat(ctx.getBean("failoverTaskExecutor", TaskExecutor.class))
                        .isNotInstanceOf(BoundedTaskExecutor.class);
                assertThat(ctx.getBeansOfType(TenantStoreFactory.class)).isNotEmpty();

                FailoverStore<Object> store = ctx.getBean(FailoverStore.class);
                assertThat(store).isInstanceOf(FailoverStoreAsync.class);
                FailoverStoreAsync<Object> async = cast(store);
                DefaultFailoverStore<Object> defaultStore = cast(requireNonNull(async.getFailoverStore()));
                assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);

                assertThat(ctx.getBeansOfType(FailoverStore.class))
                        .hasSize(1).containsOnlyKeys("failoverStore");
            });
        }
    }

    // ── Single-tenant — async executor bounded ────────────────────────────────

    @Nested
    @DisplayName("when failover.store.async-executor.concurrency-limit > 0")
    class WhenAsyncExecutorBounded {

        @Test
        @DisplayName("failoverTaskExecutor wrapped in BoundedTaskExecutor")
        void taskExecutorBounded() {
            BASE_RUNNER.withPropertyValues(
                    "failover.store.async=true",
                    "failover.store.async-executor.concurrency-limit=2",
                    "failover.store.async-executor.rejection-policy=ABORT")
                .run(ctx ->
                    assertThat(ctx.getBean("failoverTaskExecutor", TaskExecutor.class))
                        .isInstanceOf(BoundedTaskExecutor.class));
        }
    }

    // ── Single-tenant — async=false ───────────────────────────────────────────

    @Nested
    @DisplayName("when failover.store.async=false")
    class WhenStoreAsyncDisabled {

        @Test
        @DisplayName("no task executor; store chain is Default→Inmemory only; single bean named failoverStore")
        void storeChainWithoutAsync() {
            BASE_RUNNER.withPropertyValues("failover.store.async=false").run(ctx -> {
                assertThat(ctx.getBeansOfType(TaskExecutor.class)).doesNotContainKey("failoverTaskExecutor");

                FailoverStore<Object> store = ctx.getBean(FailoverStore.class);
                assertThat(store).isInstanceOf(DefaultFailoverStore.class).isNotInstanceOf(FailoverStoreAsync.class);
                DefaultFailoverStore<Object> defaultStore = cast(store);
                assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);

                assertThat(ctx.getBeansOfType(FailoverStore.class))
                        .hasSize(1).containsOnlyKeys("failoverStore");
            });
        }
    }

    // ── Multi-tenant — async=true ─────────────────────────────────────────────

    @Nested
    @DisplayName("when multitenant enabled and async=true")
    class WhenMultiTenantAsyncEnabled {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("MultiTenant→Async→Default chain; task executor registered; single store bean; single factory")
        void multiTenantAsyncChain() {
            BASE_RUNNER
                .withPropertyValues(
                        "failover.store.multitenant.enabled=true",
                        "failover.store.async=true")
                .withUserConfiguration(MultiTenantTestConfig.class)
                .run(ctx -> {
                    FailoverStore<Object> store = ctx.getBean(FailoverStore.class);
                    assertThat(store).isInstanceOf(MultiTenantFailoverStore.class);

                    MultiTenantFailoverStore<Object> multiTenant = cast(store);
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

                    assertThat(ctx.getBeansOfType(TaskExecutor.class)).containsKey("failoverTaskExecutor");
                    assertThat(ctx.getBeansOfType(FailoverStore.class)).hasSize(1).containsOnlyKeys("failoverStore");
                    assertThat(ctx.getBeansOfType(TenantStoreFactory.class)).hasSize(1);
                });
        }
    }

    // ── Multi-tenant — async=false ────────────────────────────────────────────

    @Nested
    @DisplayName("when multitenant enabled and async=false")
    class WhenMultiTenantAsyncDisabled {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("MultiTenant→Default chain (no async); no task executor; single store bean")
        void multiTenantSyncChain() {
            BASE_RUNNER
                .withPropertyValues(
                        "failover.store.multitenant.enabled=true",
                        "failover.store.async=false")
                .withUserConfiguration(MultiTenantTestConfig.class)
                .run(ctx -> {
                    FailoverStore<Object> store = ctx.getBean(FailoverStore.class);
                    assertThat(store).isInstanceOf(MultiTenantFailoverStore.class);

                    MultiTenantFailoverStore<Object> multiTenant = cast(store);
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

                    assertThat(ctx.getBeansOfType(TaskExecutor.class)).doesNotContainKey("failoverTaskExecutor");
                    assertThat(ctx.getBeansOfType(FailoverStore.class)).hasSize(1).containsOnlyKeys("failoverStore");
                });
        }
    }

    // ── Store type: caffeine ──────────────────────────────────────────────────

    @Nested
    @DisplayName("when store type is caffeine")
    class WhenStoreCaffeine {

        @Test
        @DisplayName("basic beans present; store chain is Async→Default→Caffeine")
        void caffeineStoreChain() {
            BASE_RUNNER.withPropertyValues("failover.store.type=caffeine").run(ctx -> {
                assertBasicBean(ctx);
                FailoverStore<Object> store = ctx.getBean(FailoverStore.class);
                assertThat(store).isInstanceOf(FailoverStoreAsync.class);
                FailoverStoreAsync<Object> async = cast(store);
                DefaultFailoverStore<Object> defaultStore = cast(requireNonNull(async.getFailoverStore()));
                assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreCaffeine.class);
            });
        }
    }

    // ── Store type: caffeine + async=false ────────────────────────────────────

    @Nested
    @DisplayName("when store type is caffeine and async=false")
    class WhenStoreCaffeineAsyncDisabled {

        @Test
        @DisplayName("store chain is Default→Caffeine only (no async wrapping)")
        void caffeineStoreNoAsync() {
            BASE_RUNNER.withPropertyValues("failover.store.type=caffeine", "failover.store.async=false").run(ctx -> {
                FailoverStore<Object> store = ctx.getBean(FailoverStore.class);
                assertThat(store).isInstanceOf(DefaultFailoverStore.class).isNotInstanceOf(FailoverStoreAsync.class);
                assertThat(requireNonNull(((DefaultFailoverStore<Object>) store).getFailoverStore()))
                        .isInstanceOf(FailoverStoreCaffeine.class);
            });
        }
    }

    // ── Store type: jdbc ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("when store type is jdbc")
    class WhenStoreJdbc {

        @Test
        @DisplayName("basic beans present; store chain is Async→Default→Jdbc")
        void jdbcStoreChain() {
            JDBC_BASE_RUNNER
                .withPropertyValues("failover.store.type=jdbc", "failover.store.jdbc.table-prefix=DEAL_")
                .run(ctx -> {
                    assertBasicBean(ctx);
                    FailoverStore<Object> store = ctx.getBean(FailoverStore.class);
                    assertThat(store).isInstanceOf(FailoverStoreAsync.class);
                    FailoverStoreAsync<Object> async = cast(store);
                    DefaultFailoverStore<Object> defaultStore = cast(requireNonNull(async.getFailoverStore()));
                    assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreJdbc.class);
                });
        }
    }

    // ── Store type: jdbc + async=false ────────────────────────────────────────

    @Nested
    @DisplayName("when store type is jdbc and async=false")
    class WhenStoreJdbcAsyncDisabled {

        @Test
        @DisplayName("store chain is Default→Jdbc only (no async wrapping)")
        void jdbcStoreNoAsync() {
            JDBC_BASE_RUNNER
                .withPropertyValues(
                        "failover.store.type=jdbc",
                        "failover.store.jdbc.table-prefix=DEAL_",
                        "failover.store.async=false")
                .run(ctx -> {
                    FailoverStore<Object> store = ctx.getBean(FailoverStore.class);
                    assertThat(store).isInstanceOf(DefaultFailoverStore.class).isNotInstanceOf(FailoverStoreAsync.class);
                    assertThat(requireNonNull(((DefaultFailoverStore<Object>) store).getFailoverStore()))
                            .isInstanceOf(FailoverStoreJdbc.class);
                });
        }
    }

    // ── Custom overrides (@ConditionalOnMissingBean) ──────────────────────────

    @Nested
    @DisplayName("when store type is custom (user-provided TenantStoreFactory)")
    class WhenStoreTypeCustom {

        @Test
        @DisplayName("basic beans present; exactly one custom factory; store assembled via custom factory")
        void customStoreFactory() {
            BASE_RUNNER
                .withPropertyValues("failover.store.type=custom")
                .withUserConfiguration(CustomStoreFactoryConfig.class)
                .run(ctx -> {
                    assertBasicBean(ctx);
                    assertThat(ctx.getBeansOfType(TenantStoreFactory.class)).hasSize(1);

                    FailoverStore<Object> store = ctx.getBean(FailoverStore.class);
                    assertThat(store).isInstanceOf(FailoverStoreAsync.class);
                    FailoverStoreAsync<Object> async = cast(store);
                    DefaultFailoverStore<Object> defaultStore = cast(requireNonNull(async.getFailoverStore()));
                    assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);
                });
        }
    }

    @Nested
    @DisplayName("when custom failoverTaskExecutor bean provided")
    class WhenCustomTaskExecutor {

        @Test
        @DisplayName("custom failoverTaskExecutor used via @ConditionalOnMissingBean")
        void customTaskExecutorUsed() {
            BASE_RUNNER
                .withPropertyValues("failover.store.async=true")
                .withUserConfiguration(CustomTaskExecutorConfig.class)
                .run(ctx ->
                    assertThat(ctx.getBean("failoverTaskExecutor", TaskExecutor.class))
                        .isInstanceOf(SyncTaskExecutor.class));
        }
    }

    // ── mergeAllowedPayloadClasses — pure unit tests, no Spring context ───────

    @Nested
    @DisplayName("mergeAllowedPayloadClasses — auto-derived exact class names + configured entries (audit I-02)")
    class MergeAllowedPayloadClasses {

        @Test
        @DisplayName("adds the EXACT class name of scanner-discovered payload types to the configured entries")
        void mergesScannerExactNamesWithConfigured() {
            FailoverScanner scanner = org.mockito.Mockito.mock(FailoverScanner.class);
            doReturn(Set.of(SampleType.class)).when(scanner).findAllPayloadTypes();

            var merged = FailoverStoreAutoConfiguration.mergeAllowedPayloadClasses(
                    java.util.List.of("com.acme.extra"), scanner);

            assertThat(merged).contains("com.acme.extra", SampleType.class.getName());
            assertThat(merged).doesNotContain(SampleType.class.getPackageName());
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
                    javax.sql.DataSource.class,
                    jakarta.annotation.PostConstruct.class,
                    SampleType.class))
                .when(scanner).findAllPayloadTypes();

            var merged = FailoverStoreAutoConfiguration.mergeAllowedPayloadClasses(java.util.List.of(), scanner);

            assertThat(merged)
                    .containsExactly(SampleType.class.getName())
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
        @DisplayName("adds a default-package payload type by its (dot-free) exact class name, never an empty prefix")
        void addsDefaultPackageTypeByExactName() throws ClassNotFoundException {
            Class<?> defaultPackageType = Class.forName("DefaultPackagePayloadType");
            assertThat(defaultPackageType.getPackageName()).isEmpty();
            FailoverScanner scanner = org.mockito.Mockito.mock(FailoverScanner.class);
            org.mockito.Mockito.doReturn(Set.of(defaultPackageType)).when(scanner).findAllPayloadTypes();

            var merged = FailoverStoreAutoConfiguration.mergeAllowedPayloadClasses(java.util.List.of(), scanner);

            assertThat(merged).containsExactly("DefaultPackagePayloadType");
            assertThat(merged).doesNotContain("");
        }
    }

    static class SampleType { }
}
