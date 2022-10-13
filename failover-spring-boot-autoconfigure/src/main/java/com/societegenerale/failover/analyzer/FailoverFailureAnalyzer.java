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

import com.societegenerale.failover.configuration.FailoverJdbcStoreAutoConfiguration;
import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.FailoverType;
import com.societegenerale.failover.properties.StoreType;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.analyzer.AbstractInjectionFailureAnalyzer;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import java.util.Arrays;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author Anand Manissery
 */
public class FailoverFailureAnalyzer extends AbstractInjectionFailureAnalyzer<NoSuchBeanDefinitionException> implements Ordered, EnvironmentAware {

    private Environment environment;

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NoSuchBeanDefinitionException cause, String description) {
        if(FailoverStore.class.isAssignableFrom(requireNonNull(cause.getBeanType()))) {
            String message = format("Invalid FailoverStore configuration! %s required %s that could not be found.%n", description , this.getBeanDescription(cause));
            String action = getActionForFailoverStore(environment.getProperty("failover.store.type", StoreType.class, StoreType.CUSTOM));
            return new FailureAnalysis(message, action, cause);
        }
        if(FailoverExecution.class.isAssignableFrom(cause.getBeanType())) {
            String message = format("Invalid FailoverExecution configuration! %s required %s that could not be found.%n", description , this.getBeanDescription(cause));
            String action = getActionForFailoverExecution(environment.getProperty("failover.type", FailoverType.class, FailoverType.CUSTOM));
            return new FailureAnalysis(message, action, cause);
        }
        if(description != null && description.contains(getClassName(FailoverJdbcStoreAutoConfiguration.class)) && getClassName(cause.getBeanType()).contains("JdbcTemplate") ) {
            String message = format("Invalid FailoverStore configuration! %s required %s that could not be found.%n",  description , this.getBeanDescription(cause));
            String action = format("For FailoverStore '%s', consider defining %s for FailoverStoreJdbc in your configuration Or select a non jdbc FailoverStore.", StoreType.JDBC, this.getBeanDescription(cause));
            return new FailureAnalysis(message, action, cause);
        }
        return null;
    }

    private String getActionForFailoverStore(StoreType storeType) {
        switch (storeType) {
            case CAFFEINE :  return format("For FailoverStore '%s', you must include 'com.github.ben-manes.caffeine:caffeine' dependency.", storeType);

            case JDBC : return format("For FailoverStore '%s', you must provide 'JdbcTemplate' bean.", storeType);

            default : return format("For FailoverStore '%s', Either configured to available stores { %s } by configuring 'failover.store.type' property " +
                            "OR Consider defining a bean of type '%s' in your configuration by setting 'failover.store.type=custom'.",
                    StoreType.CUSTOM, Arrays.toString(StoreType.values()), FailoverStore.class);
        }
    }

    private String getActionForFailoverExecution(FailoverType type) {
        if (type == FailoverType.RESILIENCE) {
            return format("For FailoverExecution '%s', you must include 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j' dependency.", type);
        }
        return format("For FailoverExecution '%s', Either configured to available type { %s } by configuring 'failover.type' property " +
                        "OR Consider defining a bean of type '%s' in your configuration by setting 'failover.type=custom'.",
                StoreType.CUSTOM, Arrays.toString(FailoverType.values()), FailoverStore.class);
    }

    private String getClassName(Class<?> clazz) {
        return clazz==null ? "": clazz.getName();
    }

    private String getBeanDescription(NoSuchBeanDefinitionException cause) {
        String beanType = null;
        ResolvableType type = cause.getResolvableType();
        if (type != null) {
            Class<?> typeClass = type.getRawClass();
            beanType = typeClass!=null ? typeClass.getName() : null;
        }
        return "a bean of type '" + beanType + "'";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }
}