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

/**
 * Spring {@code BeanFactory}-based lookups that resolve the named {@code KeyGenerator},
 * {@code ExpiryPolicy} and {@code PayloadSplitter} beans referenced by
 * {@link com.societegenerale.failover.annotations.Failover#keyGenerator()},
 * {@link com.societegenerale.failover.annotations.Failover#expiryPolicy()} and
 * {@link com.societegenerale.failover.annotations.Failover#payloadSplitter()}.
 *
 * <p>Each {@code BeanFactory*Lookup} implements the corresponding lookup SPI from
 * {@code failover-core} by fetching the bean by name from the Spring context, so a
 * {@code @Failover} method can point at a custom strategy by its bean name. This module exists to keep
 * the Spring dependency out of {@code failover-core}, which stays framework-agnostic.
 */
package com.societegenerale.failover.lookup;
