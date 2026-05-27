package com.societegenerale.failover.processor;

import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreInmemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultFailoverStoreBeanPostProcessorTest {

    private DefaultFailoverStoreBeanPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DefaultFailoverStoreBeanPostProcessor();
    }

    @Test
    @DisplayName("should wrap raw FailoverStore with DefaultFailoverStore")
    void rawFailoverStoreWrapsWithDefault() {
        FailoverStoreInmemory<Object> rawStore = new FailoverStoreInmemory<>();

        Object result = processor.postProcessBeforeInitialization(rawStore, "failoverStore");

        assertThat(result).isInstanceOf(DefaultFailoverStore.class);
    }

    @Test
    @DisplayName("should preserve original bean as the inner store after wrapping")
    @SuppressWarnings("unchecked")
    void rawFailoverStoreInnermostIsOriginalBean() {
        FailoverStoreInmemory<Object> rawStore = new FailoverStoreInmemory<>();

        Object result = processor.postProcessBeforeInitialization(rawStore, "failoverStore");

        assertThat(result).isNotNull();
        DefaultFailoverStore<Object> defaultStore = (DefaultFailoverStore<Object>) result;
        assertThat(requireNonNull(defaultStore.getFailoverStore())).isSameAs(rawStore);
    }

    @Test
    @DisplayName("should return FailoverStoreAsync as-is without wrapping")
    void alreadyWrappedWithAsyncReturnsSameInstance() {
        FailoverStoreAsync<Object> asyncStore = new FailoverStoreAsync<>(new FailoverStoreInmemory<>());

        Object result = processor.postProcessBeforeInitialization(asyncStore, "failoverStoreAsync");

        assertThat(result).isSameAs(asyncStore);
    }

    @Test
    @DisplayName("should return DefaultFailoverStore as-is without double-wrapping")
    void alreadyWrappedWithDefaultReturnsSameInstance() {
        DefaultFailoverStore<Object> defaultStore = new DefaultFailoverStore<>(new FailoverStoreInmemory<>());

        Object result = processor.postProcessBeforeInitialization(defaultStore, "defaultFailoverStore");

        assertThat(result).isSameAs(defaultStore);
    }

    @Test
    @DisplayName("should return non-FailoverStore bean unchanged")
    void nonFailoverStoreBeanReturnsSameInstance() {
        String nonStoreBean = "someBean";

        Object result = processor.postProcessBeforeInitialization(nonStoreBean, "someBean");

        assertThat(result).isSameAs(nonStoreBean);
    }

    @Test
    @DisplayName("should return arbitrary object bean unchanged")
    void arbitraryObjectReturnsSameInstance() {
        Object arbitraryBean = new Object();

        Object result = processor.postProcessBeforeInitialization(arbitraryBean, "arbitraryBean");

        assertThat(result).isSameAs(arbitraryBean);
    }

    @Test
    @DisplayName("should wrap regardless of bean name")
    @SuppressWarnings("unchecked")
    void rawFailoverStoreWrapsRegardlessOfBeanName() {
        FailoverStoreInmemory<Object> rawStore = new FailoverStoreInmemory<>();

        Object result = processor.postProcessBeforeInitialization(rawStore, "anyBeanNameWhatsoever");

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(DefaultFailoverStore.class);
        DefaultFailoverStore<Object> defaultStore = (DefaultFailoverStore<Object>) result;
        assertThat(requireNonNull(defaultStore.getFailoverStore())).isSameAs(rawStore);
    }

    @Test
    @DisplayName("should have order 1 to run before AsyncFailoverStoreBeanPostProcessor")
    void orderIsOne() {
        assertThat(processor.getOrder()).isEqualTo(1);
    }
}