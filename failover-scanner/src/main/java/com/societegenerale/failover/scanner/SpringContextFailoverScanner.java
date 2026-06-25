/*
 * Copyright 2022-2026, Société Générale All rights reserved.
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

package com.societegenerale.failover.scanner;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.scanner.FailoverScannerException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private volatile Set<Class<?>> payloadTypes = Set.of();

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
        Set<Class<?>> discoveredPayloadTypes = new LinkedHashSet<>();
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
                    warnIfNotAdvisable(userClass, method, annotation);
                    warnIfInvalidScatterConfig(userClass, method, annotation);
                    collectPayloadType(method, discoveredPayloadTypes);
                },
                method -> !method.isBridge() && !method.isSynthetic()
            );
        }
        this.failoverMap = discovered;
        this.payloadTypes = Set.copyOf(discoveredPayloadTypes);
        log.info("SpringContextFailoverScanner discovered {} @Failover annotation(s) covering {} payload type(s).",
                failoverMap.size(), payloadTypes.size());
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

    @Override
    public Set<Class<?>> findAllPayloadTypes() {
        return payloadTypes;
    }

    /**
     * Resolves the payload type a {@code @Failover} method ultimately stores: its return type, or
     * the element/component type when the method returns a {@link Collection} or array (the
     * collection wrapper itself — e.g. {@code java.util.List} — is never the stored payload).
     * Unresolvable element types (raw or wildcard generics) and {@code void} are skipped.
     */
    private void collectPayloadType(Method method, Set<Class<?>> sink) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return;
        }
        if (returnType.isArray()) {
            sink.add(returnType.getComponentType());
            return;
        }
        if (Collection.class.isAssignableFrom(returnType)) {
            Class<?> element = resolveCollectionElement(method.getGenericReturnType());
            if (element != null) {
                sink.add(element);
            }
            return;
        }
        sink.add(returnType);
    }

    @Nullable
    private Class<?> resolveCollectionElement(Type genericReturnType) {
        if (genericReturnType instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> elementClass) {
                return elementClass;
            }
        }
        return null;
    }

    /**
     * Warns at startup when a discovered {@code @Failover} method cannot be advised by the Spring AOP
     * CGLIB proxy, so a silently-inactive failover is visible rather than a runtime surprise (audit A8).
     *
     * <p>The proxy can only intercept a <b>public, non-static, non-final</b> method declared on a
     * <b>non-final</b> concrete class, with the annotation present <b>directly on that concrete method</b>
     * (not inherited from an interface/superclass — CGLIB advises the implementation, not the supertype).
     * Self-invocation (a bean calling its own annotated method) bypasses the proxy too, but that is a
     * runtime call-graph property and cannot be detected here — see the documentation.
     *
     * @param userClass  the concrete (CGLIB-unwrapped) bean class
     * @param method     the method carrying (directly or by inheritance) the {@code @Failover} annotation
     * @param annotation the discovered annotation
     */
    private void warnIfNotAdvisable(Class<?> userClass, Method method, Failover annotation) {
        // Interface beans advised by JDK dynamic proxies — Feign clients, Spring Data repositories,
        // @HttpExchange clients, etc. — are proxied at the interface level, so the annotation belongs on
        // the interface method and the concrete-class CGLIB rules below do not apply. JDK proxy classes
        // carry no usable method annotations either. Skip both to avoid false-positive warnings.
        if (userClass.isInterface() || java.lang.reflect.Proxy.isProxyClass(userClass)) {
            return;
        }
        List<String> reasons = new ArrayList<>();
        if (!method.isAnnotationPresent(Failover.class)) {
            reasons.add(("@Failover is declared on a supertype/interface, not directly on the concrete method — "
                    + "override '%s' in '%s' and put @Failover on that override (CGLIB advises the implementation "
                    + "method, not the interface)").formatted(method.getName(), userClass.getSimpleName()));
        }
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers)) {
            reasons.add("method is not public");
        }
        if (Modifier.isStatic(modifiers)) {
            reasons.add("method is static");
        }
        if (Modifier.isFinal(modifiers)) {
            reasons.add("method is final (CGLIB cannot override it)");
        }
        if (Modifier.isFinal(userClass.getModifiers())) {
            reasons.add("declaring class '%s' is final (CGLIB cannot subclass it)".formatted(userClass.getSimpleName()));
        }
        if (!reasons.isEmpty()) {
            log.warn("Failover '{}' on {}#{} will NOT be applied — the method cannot be intercepted by the Spring AOP proxy: {}. "
                    + "The annotation has no effect until fixed.",
                    annotation.name(), userClass.getSimpleName(), method.getName(), String.join("; ", reasons));
        }
    }

    /**
     * Warns at startup when a {@code @Failover} sets {@code recoverAll=true} but configures no
     * {@code payloadSplitter} (audit A10). Recover-all relies on a {@link com.societegenerale.failover.core.payload.splitter.PayloadSplitter}
     * to slice and merge the whole referential; without one the scatter path is never entered, so the
     * flag is silently ignored and the call falls back to single-key recover. Surfacing it at boot avoids
     * a feature that looks enabled but does nothing.
     *
     * @param userClass  the concrete bean class (used only for the message)
     * @param method     the annotated method (used only for the message)
     * @param annotation the discovered annotation
     */
    private void warnIfInvalidScatterConfig(Class<?> userClass, Method method, Failover annotation) {
        if (annotation.recoverAll() && annotation.payloadSplitter().isBlank()) {
            log.warn("Failover '{}' on {}#{} sets recoverAll=true but no payloadSplitter — recover-all needs a "
                    + "PayloadSplitter to slice/merge the referential. Recover-all will NOT run; the call falls back "
                    + "to single-key recover. Set @Failover(payloadSplitter=\"...\") or remove recoverAll.",
                    annotation.name(), userClass.getSimpleName(), method.getName());
        }
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
