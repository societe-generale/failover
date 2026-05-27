package com.societegenerale.failover.configuration;

import com.societegenerale.failover.MyTestApplication;
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.processor.AsyncFailoverStoreBeanPostProcessor;
import com.societegenerale.failover.processor.DefaultFailoverStoreBeanPostProcessor;
import com.societegenerale.failover.store.FailoverStoreAsync;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncBeanProcessorConfigurationTest {

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
        @DisplayName("should register AsyncFailoverStoreBeanPostProcessor bean")
        void shouldRegisterAsyncProcessor() {
            assertThat(applicationContext.getBeansOfType(AsyncFailoverStoreBeanPostProcessor.class)).isNotEmpty();
        }

        @Test
        @DisplayName("should register DefaultFailoverStoreBeanPostProcessor bean")
        void shouldRegisterDefaultProcessor() {
            assertThat(applicationContext.getBeansOfType(DefaultFailoverStoreBeanPostProcessor.class)).isNotEmpty();
        }

        @Test
        @DisplayName("should wrap FailoverStore as FailoverStoreAsync wrapping DefaultFailoverStore")
        @SuppressWarnings("unchecked")
        void shouldWrapStoreAsAsyncAroundDefault() throws Exception {
            Object target = AopUtils.isAopProxy(failoverStore)
                    ? ((Advised) failoverStore).getTargetSource().getTarget()
                    : failoverStore;
            assertThat(target).isNotNull();
            assertThat(target).isInstanceOf(FailoverStoreAsync.class);
            FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) cast(target)).getFailoverStore());
            assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
        }

        @Test
        @DisplayName("should have DefaultFailoverStoreBeanPostProcessor order less than AsyncFailoverStoreBeanPostProcessor")
        void defaultProcessorOrderShouldBeBeforeAsyncProcessor() {
            DefaultFailoverStoreBeanPostProcessor defaultProcessor =
                    applicationContext.getBean(DefaultFailoverStoreBeanPostProcessor.class);
            AsyncFailoverStoreBeanPostProcessor asyncProcessor =
                    applicationContext.getBean(AsyncFailoverStoreBeanPostProcessor.class);
            assertThat(defaultProcessor.getOrder())
                    .as("DefaultFailoverStoreBeanPostProcessor must execute before AsyncFailoverStoreBeanPostProcessor")
                    .isLessThan(asyncProcessor.getOrder());
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
        @DisplayName("should NOT register AsyncFailoverStoreBeanPostProcessor bean")
        void shouldNotRegisterAsyncProcessor() {
            assertThat(applicationContext.getBeansOfType(AsyncFailoverStoreBeanPostProcessor.class)).isEmpty();
        }

        @Test
        @DisplayName("should still register DefaultFailoverStoreBeanPostProcessor bean")
        void shouldRegisterDefaultProcessor() {
            assertThat(applicationContext.getBeansOfType(DefaultFailoverStoreBeanPostProcessor.class)).isNotEmpty();
        }

        @Test
        @DisplayName("should wrap FailoverStore as DefaultFailoverStore only — no async wrapping")
        void shouldWrapStoreAsDefaultOnly() throws Exception {
            Object target = AopUtils.isAopProxy(failoverStore)
                    ? ((Advised) failoverStore).getTargetSource().getTarget()
                    : failoverStore;
            assertThat(target)
                    .isInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(FailoverStoreAsync.class);
        }
    }
}