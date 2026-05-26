package com.societegenerale.failover.processor;

import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.FailoverStoreAsync;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import static com.societegenerale.failover.core.util.CastingUtils.cast;

public class FailoverStoreBeanPostProcessor implements BeanPostProcessor {

    @Override
    public @Nullable Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        if (bean instanceof FailoverStore
                && !(bean instanceof FailoverStoreAsync)
                && !(bean instanceof DefaultFailoverStore)) {
            return new FailoverStoreAsync<>(new DefaultFailoverStore<>(cast(bean)));
        }
        return bean;
    }
}