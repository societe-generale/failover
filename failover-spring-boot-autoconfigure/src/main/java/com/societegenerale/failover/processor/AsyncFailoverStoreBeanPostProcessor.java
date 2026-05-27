package com.societegenerale.failover.processor;

import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import static com.societegenerale.failover.core.util.CastingUtils.cast;

public class AsyncFailoverStoreBeanPostProcessor implements BeanPostProcessor, Ordered {

    @Override
    public @Nullable Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        if (bean instanceof DefaultFailoverStore) {
            return new FailoverStoreAsync<>(cast(bean));
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return 2;
    }
}