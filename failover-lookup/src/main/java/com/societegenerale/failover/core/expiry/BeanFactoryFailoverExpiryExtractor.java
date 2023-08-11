package com.societegenerale.failover.core.expiry;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

import java.time.temporal.ChronoUnit;

import static java.util.Objects.requireNonNull;

public class BeanFactoryFailoverExpiryExtractor extends AbstractFailoverExpiryExtractor implements BeanFactoryAware {

    private ConfigurableBeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = (ConfigurableBeanFactory) beanFactory;
    }

    @Override
    protected long resolveExpiryDuration(String expression) {
        return Long.parseLong(requireNonNull(beanFactory.resolveEmbeddedValue(expression)));
    }

    @Override
    protected ChronoUnit resolveExpiryUnit(String expression) {
        return ChronoUnit.valueOf(requireNonNull(beanFactory.resolveEmbeddedValue(expression)).toUpperCase());
    }

}
