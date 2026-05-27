package com.societegenerale.failover.processor;

import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreInmemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncFailoverStoreBeanPostProcessorTest {

    private AsyncFailoverStoreBeanPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new AsyncFailoverStoreBeanPostProcessor();
    }

    @Test
    @DisplayName("should wrap DefaultFailoverStore with FailoverStoreAsync")
    void defaultFailoverStoreWrapsWithAsync() {
        DefaultFailoverStore<Object> defaultStore = new DefaultFailoverStore<>(new FailoverStoreInmemory<>());

        Object result = processor.postProcessBeforeInitialization(defaultStore, "failoverStore");

        assertThat(result).isInstanceOf(FailoverStoreAsync.class);
    }

    @Test
    @DisplayName("should preserve DefaultFailoverStore as the inner store after wrapping")
    @SuppressWarnings("unchecked")
    void defaultFailoverStoreIsInnerStore() {
        DefaultFailoverStore<Object> defaultStore = new DefaultFailoverStore<>(new FailoverStoreInmemory<>());

        Object result = processor.postProcessBeforeInitialization(defaultStore, "failoverStore");

        assertThat(result).isNotNull();
        FailoverStoreAsync<Object> asyncStore = (FailoverStoreAsync<Object>) result;
        assertThat(requireNonNull(asyncStore.getFailoverStore())).isSameAs(defaultStore);
    }

    @Test
    @DisplayName("should return raw FailoverStore as-is without wrapping")
    void rawFailoverStoreReturnsSameInstance() {
        FailoverStoreInmemory<Object> rawStore = new FailoverStoreInmemory<>();

        Object result = processor.postProcessBeforeInitialization(rawStore, "failoverStore");

        assertThat(result).isSameAs(rawStore);
    }

    @Test
    @DisplayName("should return FailoverStoreAsync as-is without double-wrapping")
    void alreadyWrappedWithAsyncReturnsSameInstance() {
        FailoverStoreAsync<Object> asyncStore = new FailoverStoreAsync<>(new DefaultFailoverStore<>(new FailoverStoreInmemory<>()));

        Object result = processor.postProcessBeforeInitialization(asyncStore, "failoverStoreAsync");

        assertThat(result).isSameAs(asyncStore);
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
    void defaultFailoverStoreWrapsRegardlessOfBeanName() {
        DefaultFailoverStore<Object> defaultStore = new DefaultFailoverStore<>(new FailoverStoreInmemory<>());

        Object result = processor.postProcessBeforeInitialization(defaultStore, "anyBeanNameWhatsoever");

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(FailoverStoreAsync.class);
        FailoverStoreAsync<Object> asyncStore = (FailoverStoreAsync<Object>) result;
        assertThat(requireNonNull(asyncStore.getFailoverStore())).isSameAs(defaultStore);
    }

    @Test
    @DisplayName("should have order 2 to run after DefaultFailoverStoreBeanPostProcessor")
    void orderIsTwo() {
        assertThat(processor.getOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("should have higher order than DefaultFailoverStoreBeanPostProcessor to guarantee execution after it")
    void orderIsHigherThanDefaultProcessor() {
        DefaultFailoverStoreBeanPostProcessor defaultProcessor = new DefaultFailoverStoreBeanPostProcessor();
        assertThat(processor.getOrder()).isGreaterThan(defaultProcessor.getOrder());
    }
}