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

package com.societegenerale.failover.observable.scanner;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.observable.scanner.FailoverScanner;
import com.societegenerale.failover.core.observable.scanner.FailoverScannerException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link FailoverScanner} backed by the Spring {@link ApplicationContext}.
 *
 * <p>Implements {@link SmartInitializingSingleton} so it runs after <em>all</em>
 * singleton beans have been instantiated — the correct lifecycle point to see every
 * registered bean. It enumerates bean definitions, unwraps CGLIB proxies via
 * {@link ClassUtils#getUserClass}, then walks each class's method hierarchy with
 * {@link ReflectionUtils#doWithMethods} using Spring's
 * {@link AnnotationUtils#findAnnotation} so that {@code @Failover} placed on an
 * interface method is found even when the concrete class does not repeat it.
 *
 * <p>Works with <strong>Spring-managed beans only</strong> — methods on plain Java objects
 * not registered in the Spring context are not visible to this scanner.
 *
 * @author Anand Manissery
 */
@Slf4j
public class SpringContextFailoverScanner
        implements FailoverScanner, ApplicationContextAware, SmartInitializingSingleton {

    private ApplicationContext applicationContext;

    private volatile Map<String, Failover> failoverMap = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(@Nullable ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Scans all registered singleton beans for methods annotated with {@link Failover}.
     * Called by Spring after every singleton bean has been instantiated.
     */
    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Failover> discovered = new ConcurrentHashMap<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type = safeGetType(beanName);
            if (type == null) continue;
            Class<?> userClass = ClassUtils.getUserClass(type);
            ReflectionUtils.doWithMethods(userClass,
                method -> {
                    Failover annotation = AnnotationUtils.findAnnotation(method, Failover.class);
                    if (annotation == null) return;
                    if (discovered.putIfAbsent(annotation.name(), annotation) != null) {
                        throw new FailoverScannerException(
                            "Duplicate @Failover name '%s' found. Each failover must have a unique name."
                                .formatted(annotation.name()));
                    }
                },
                method -> !method.isBridge() && !method.isSynthetic()
            );
        }
        this.failoverMap = discovered;
        log.info("SpringContextFailoverScanner discovered {} @Failover annotation(s).", failoverMap.size());
        warnOnDomainExpirtyMismatch(discovered);
    }

    @Override
    public @Nullable Failover findFailoverByName(String name) {
        return failoverMap.get(name);
    }

    @Override
    public List<Failover> findAllFailover() {
        return new ArrayList<>(failoverMap.values());
    }

    private void warnOnDomainExpirtyMismatch(Map<String, Failover> discovered) {
        discovered.values().stream()
            .filter(f -> !f.domain().isBlank())
            .collect(Collectors.groupingBy(Failover::domain))
            .forEach((domain, list) -> {
                long distinct = list.stream()
                    .map(f -> f.expiryDuration() + "|" + f.expiryUnit().name())
                    .distinct().count();
                if (distinct > 1) {
                    log.warn("Failover domain '{}' contains {} failovers with different expiry configurations. " +
                             "Last writer wins per store entry — align expiry to avoid inconsistency.", domain, list.size());
                }
            });
    }

    @Nullable
    private Class<?> safeGetType(String beanName) {
        try {
            return applicationContext.getType(beanName);
        } catch (Exception e) {
            log.debug("Could not determine type for bean '{}', skipping. Cause: {}", beanName, e.getMessage());
            return null;
        }
    }
}
