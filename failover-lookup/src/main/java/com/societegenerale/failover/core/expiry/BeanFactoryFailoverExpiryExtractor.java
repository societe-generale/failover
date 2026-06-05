package com.societegenerale.failover.core.expiry;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

import java.time.temporal.ChronoUnit;

import static java.util.Objects.requireNonNull;

/**
 * {@link AbstractFailoverExpiryExtractor} that resolves expiry expressions via
 * Spring's {@link ConfigurableBeanFactory#resolveEmbeddedValue}, supporting
 * property placeholders (e.g. {@code ${my.ttl}}) and SpEL expressions.
 *
 * @author Anand Manissery
 */
public class BeanFactoryFailoverExpiryExtractor extends AbstractFailoverExpiryExtractor implements BeanFactoryAware {

    private ConfigurableBeanFactory beanFactory;

    /**
     * Injects the Spring {@link BeanFactory} used for expression resolution.
     *
     * @param beanFactory the bean factory; must implement {@link ConfigurableBeanFactory}
     * @throws BeansException if setting the bean factory fails
     */
    @Override
    public void setBeanFactory(@NonNull BeanFactory beanFactory) throws BeansException {
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
