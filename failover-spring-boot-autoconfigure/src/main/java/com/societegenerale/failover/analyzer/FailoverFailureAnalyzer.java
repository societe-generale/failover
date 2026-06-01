/*
 * Copyright 2022-2023, Société Générale All rights reserved.
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

package com.societegenerale.failover.analyzer;

import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.FailoverType;
import com.societegenerale.failover.properties.StoreType;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.analyzer.AbstractInjectionFailureAnalyzer;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Spring Boot {@link org.springframework.boot.diagnostics.FailureAnalyzer} that produces
 * actionable startup diagnostics when a required failover bean is missing.
 *
 * <p>Intercepts {@link org.springframework.beans.factory.NoSuchBeanDefinitionException}
 * and checks whether the missing bean is a {@code FailoverStore}, a {@code FailoverExecution},
 * or a {@code JdbcTemplate} needed by the JDBC store. For each case it emits a targeted
 * message that names the missing dependency and describes how to resolve it
 * (e.g. adding the correct {@code failover.store.type} property or the JDBC dependency).
 *
 * @author Anand Manissery
 */
public class FailoverFailureAnalyzer extends AbstractInjectionFailureAnalyzer<NoSuchBeanDefinitionException> implements Ordered, EnvironmentAware {

    private Environment environment;

    @Override
    protected FailureAnalysis analyze(@NonNull Throwable rootFailure, NoSuchBeanDefinitionException cause, String description) {
        Class<?> beanType = cause.getBeanType();
        if (beanType != null && FailoverStore.class.isAssignableFrom(beanType)) {
            String message = "Invalid FailoverStore configuration! %s required %s that could not be found.%n".formatted(description, getBeanDescription(cause));
            String action = getActionForFailoverStore(environment.getProperty("failover.store.type", StoreType.class, StoreType.CUSTOM));
            return new FailureAnalysis(message, action, cause);
        }
        if (beanType != null && FailoverExecution.class.isAssignableFrom(beanType)) {
            String message = "Invalid FailoverExecution configuration! %s required %s that could not be found.%n".formatted(description, getBeanDescription(cause));
            String action = getActionForFailoverExecution(environment.getProperty("failover.type", FailoverType.class, FailoverType.CUSTOM));
            return new FailureAnalysis(message, action, cause);
        }
        if (description != null && description.contains("JdbcStoreConfiguration") && getClassName(beanType).contains("JdbcTemplate")) {
            String message = "Invalid FailoverStore configuration! %s required %s that could not be found.%n".formatted(description, getBeanDescription(cause));
            String action = "For FailoverStore '%s', consider defining %s for FailoverStoreJdbc in your configuration Or select a non jdbc FailoverStore.".formatted(StoreType.JDBC, getBeanDescription(cause));
            return new FailureAnalysis(message, action, cause);
        }
        return null;
    }

    private String getActionForFailoverStore(StoreType storeType) {
        return switch (storeType) {
            case CAFFEINE ->
                    "For FailoverStore '%s', you must include 'com.github.ben-manes.caffeine:caffeine' dependency.".formatted(storeType);
            case JDBC ->
                    "For FailoverStore '%s', you must provide 'JdbcTemplate' bean.".formatted(storeType);
            default ->
                    """
                    For FailoverStore '%s', Either configured to available stores { %s } by configuring 'failover.store.type' property \
                    OR Consider defining a bean of type '%s' in your configuration by setting 'failover.store.type=custom'."""
                            .formatted(storeType, Arrays.toString(StoreType.values()), FailoverStore.class);
        };
    }

    private String getActionForFailoverExecution(FailoverType type) {
        if (type == FailoverType.RESILIENCE) {
            return "For FailoverExecution '%s', you must include 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j' dependency.".formatted(type);
        }
        return """
                For FailoverExecution '%s', Either configured to available type { %s } by configuring 'failover.type' property \
                OR Consider defining a bean of type '%s' in your configuration by setting 'failover.type=custom'."""
                .formatted(type, Arrays.toString(FailoverType.values()), FailoverExecution.class);
    }

    private String getClassName(Class<?> clazz) {
        return clazz == null ? "" : clazz.getName();
    }

    private String getBeanDescription(NoSuchBeanDefinitionException cause) {
        ResolvableType type = cause.getResolvableType();
        if (type != null) {
            Class<?> typeClass = type.getRawClass();
            if (typeClass != null) {
                return "a bean of type '%s'".formatted(typeClass.getName());
            }
        }
        String beanName = cause.getBeanName();
        if (beanName != null) {
            return "a bean named '%s'".formatted(beanName);
        }
        return "an unknown bean";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public void setEnvironment(@NonNull Environment environment) {
        this.environment = environment;
    }
}
