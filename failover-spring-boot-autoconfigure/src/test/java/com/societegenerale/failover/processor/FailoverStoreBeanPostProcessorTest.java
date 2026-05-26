package com.societegenerale.failover.processor;

import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreInmemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class FailoverStoreBeanPostProcessorTest {

    private FailoverStoreBeanPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new FailoverStoreBeanPostProcessor();
    }

    @Test
    @DisplayName("should wrap raw FailoverStore with FailoverStoreAsync and DefaultFailoverStore")
    @SuppressWarnings("unchecked")
    void rawFailoverStore_wrapsWithAsyncAndDefault() {
        FailoverStoreInmemory<Object> rawStore = new FailoverStoreInmemory<>();

        Object result = processor.postProcessBeforeInitialization(rawStore, "failoverStore");
        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(FailoverStoreAsync.class);
        FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) result).getFailoverStore());
        assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
    }

    @Test
    @DisplayName("should preserve original bean as the innermost store after wrapping")
    @SuppressWarnings("unchecked")
    void rawFailoverStore_innermostIsOriginalBean() {
        FailoverStoreInmemory<Object> rawStore = new FailoverStoreInmemory<>();

        Object result = processor.postProcessBeforeInitialization(rawStore, "failoverStore");
        assertThat(result).isNotNull();
        FailoverStoreAsync<Object> async = (FailoverStoreAsync<Object>) result;
        DefaultFailoverStore<Object> defaultStore = (DefaultFailoverStore<Object>) requireNonNull(async.getFailoverStore());
        assertThat(requireNonNull(defaultStore.getFailoverStore())).isSameAs(rawStore);
    }

    @Test
    @DisplayName("should return FailoverStoreAsync as-is without double-wrapping")
    void alreadyWrappedWithAsync_returnsSameInstance() {
        FailoverStoreAsync<Object> asyncStore = new FailoverStoreAsync<>(new FailoverStoreInmemory<>());

        Object result = processor.postProcessBeforeInitialization(asyncStore, "failoverStoreAsync");

        assertThat(result).isSameAs(asyncStore);
    }

    @Test
    @DisplayName("should return DefaultFailoverStore as-is without double-wrapping")
    void alreadyWrappedWithDefault_returnsSameInstance() {
        DefaultFailoverStore<Object> defaultStore = new DefaultFailoverStore<>(new FailoverStoreInmemory<>());

        Object result = processor.postProcessBeforeInitialization(defaultStore, "defaultFailoverStore");

        assertThat(result).isSameAs(defaultStore);
    }

    @Test
    @DisplayName("should return non-FailoverStore bean unchanged")
    void nonFailoverStoreBean_returnsSameInstance() {
        String nonStoreBean = "someBean";

        Object result = processor.postProcessBeforeInitialization(nonStoreBean, "someBean");

        assertThat(result).isSameAs(nonStoreBean);
    }

    @Test
    @DisplayName("should return arbitrary object bean unchanged")
    void arbitraryObject_returnsSameInstance() {
        Object arbitraryBean = new Object();

        Object result = processor.postProcessBeforeInitialization(arbitraryBean, "arbitraryBean");

        assertThat(result).isSameAs(arbitraryBean);
    }

    @Test
    @DisplayName("should wrap regardless of bean name")
    @SuppressWarnings("unchecked")
    void rawFailoverStore_wrapsRegardlessOfBeanName() {
        FailoverStoreInmemory<Object> rawStore = new FailoverStoreInmemory<>();

        Object result = processor.postProcessBeforeInitialization(rawStore, "anyBeanNameWhatsoever");
        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(FailoverStoreAsync.class);
        FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) result).getFailoverStore());
        assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
        assertThat(requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore())).isSameAs(rawStore);
    }
}