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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.FailoverHandler;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.exception.MethodExceptionHandler;
import com.societegenerale.failover.core.exception.policy.MethodExceptionPolicy;
import com.societegenerale.failover.core.expiry.ExpiryPolicy;
import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.key.KeyGenerator;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.core.observable.publisher.CompositeObservablePublisher;
import com.societegenerale.failover.core.observable.FailoverObserver;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import com.societegenerale.failover.core.observable.scanner.FailoverScanner;
import com.societegenerale.failover.core.store.FailoverStore;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
public class BeanAssertions {

    private BeanAssertions() {
        throw new IllegalStateException("Cannot instantiate a utility class");
    }

    /**
     * Single-registration bean types — each must resolve to exactly one bean.
     */
    private static final Class<?>[] MANDATORY_BASIC_BEANS = {
            FailoverClock.class,
            FailoverStore.class,
            PayloadEnricher.class,
            RecoveredPayloadHandler.class,
            FailoverHandler.class,
            FailoverExecution.class,
            MethodExceptionHandler.class,
            MethodExceptionPolicy.class,
            FailoverExpiryExtractor.class,
            FailoverScanner.class,
            CompositeObservablePublisher.class,
            FailoverObserver.class
    };

    /**
     * Multi-registration bean types — each may have more than one registered bean.
     * Checked via {@code getBeansOfType} (not empty).
     */
    private static final Class<?>[] MANDATORY_BASIC_BEANS_COLLECTION = {
            ObservablePublisher.class,
            KeyGenerator.class,
            ExpiryPolicy.class
    };

    public static void assertBasicBean(ApplicationContext applicationContext) {
        assertBeansAreNotNull(applicationContext, MANDATORY_BASIC_BEANS);
        assertBeansAreNotEmpty(applicationContext, MANDATORY_BASIC_BEANS_COLLECTION);
    }

    public static void assertBeansAreNotNull(ApplicationContext applicationContext, Class<?>... clazzArgs) {
        asList(clazzArgs).forEach(clazz -> beanIsNotNull(applicationContext.getBean(clazz)));
    }

    public static void assertBeansAreNotEmpty(ApplicationContext applicationContext, Class<?>... clazzArgs) {
        asList(clazzArgs).forEach(clazz -> beanIsNotEmpty(applicationContext.getBeansOfType(clazz)));
    }

    public static void assertBeansAreEmpty(ApplicationContext applicationContext, Class<?>... clazzArgs) {
        asList(clazzArgs).forEach(clazz -> beanMapIsEmpty(applicationContext.getBeansOfType(clazz)));
    }

    private static <T> void beanIsNotEmpty(Map<String, T> beanMap) {
        assertThat(beanMap).isNotEmpty();
    }

    private static <T> void beanIsNotNull(T bean) {
        assertThat(bean).isNotNull();
    }

    private static <T> void beanMapIsEmpty(Map<String, T> beanMap) {
        assertThat(beanMap).isEmpty();
    }
}
