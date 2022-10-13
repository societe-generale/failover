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
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * @author Anand Manissery
 */
public class DefaultFailoverScanner implements FailoverScanner {

    private final ExceptionHandler exceptionHandler;

    private Reflections reflections;

    private Map<String, Failover> failoverMap;

    public DefaultFailoverScanner(String packageToScan) {
        this.exceptionHandler = new ReflectionsExceptionHandler();
        this.exceptionHandler.execute(()->
            this.reflections = new Reflections(packageToScan, new MethodAnnotationsScanner())
        );
        this.failoverMap = new ConcurrentHashMap<>();
        init();
    }

    @Override
    public Failover findFailoverByName(String name) {
        return failoverMap.get(name);
    }

    @Override
    public List<Failover> findAllFailover() {
        return new ArrayList<>(failoverMap.values());
    }

    private void init() {
        this.exceptionHandler.execute(() -> failoverMap = reflections.getMethodsAnnotatedWith(Failover.class)
                .stream().map(method -> method.getAnnotation(Failover.class)).collect(toMap(Failover::name, identity()))
        );
    }
}
