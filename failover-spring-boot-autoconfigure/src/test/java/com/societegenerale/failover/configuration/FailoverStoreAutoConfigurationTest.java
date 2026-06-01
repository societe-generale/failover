package com.societegenerale.failover.configuration;

import com.societegenerale.failover.MyTestApplication;
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreInmemory;
import com.societegenerale.failover.store.multitenant.TenantStoreFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.TestPropertySource;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class FailoverStoreAutoConfigurationTest {

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
}