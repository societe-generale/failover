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

package com.societegenerale.failover.core.scanner;

import com.societegenerale.failover.annotations.Failover;
import org.jspecify.annotations.Nullable;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * Default {@link FailoverScanner} backed by the Reflections library.
 * Scans the given base package for all methods annotated with {@link Failover}
 * and indexes them by name for fast lookup.
 *
 * @author Anand Manissery
 */
public class DefaultFailoverScanner implements FailoverScanner {

    private final ExceptionHandler exceptionHandler;

    private Reflections reflections;

    private Map<String, Failover> failoverMap;

    /**
     * Creates a scanner and immediately scans the given package for {@code @Failover} annotations.
     *
     * @param packageToScan base package to scan; must not be blank
     * @throws IllegalStateException if {@code packageToScan} is blank
     * @throws FailoverScannerException if the Reflections scan fails or duplicate names are found
     */
    public DefaultFailoverScanner(String packageToScan) {
        if (packageToScan == null || packageToScan.isBlank()) {
            throw new IllegalStateException(
                    "failover.package-to-scan must not be blank. " +
                    "Set it to the base package of your application (e.g. failover.package-to-scan=com.example.app).");
        }
        this.exceptionHandler = new ReflectionsExceptionHandler();
        this.exceptionHandler.execute(()->
            this.reflections = new Reflections(packageToScan, Scanners.MethodsAnnotated)
        );
        this.failoverMap = new ConcurrentHashMap<>();
        init();
    }

    @Override
    public @Nullable Failover findFailoverByName(String name) {
        return failoverMap.get(name);
    }

    @Override
    public List<Failover> findAllFailover() {
        return new ArrayList<>(failoverMap.values());
    }

    private void init() {
        this.exceptionHandler.execute(() -> failoverMap = reflections.getMethodsAnnotatedWith(Failover.class)
                .stream().map(method -> method.getAnnotation(Failover.class))
                .collect(toMap(Failover::name, identity(), (a, b) -> {
                    throw new FailoverScannerException(
                        "Duplicate @Failover name '%s' found. Each failover must have a unique name.".formatted(a.name()));
                }))
        );
    }
}
