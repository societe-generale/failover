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
 * Scanner SPI for discovering {@code @Failover}-annotated methods.
 *
 * <p>{@link com.societegenerale.failover.core.scanner.FailoverScanner} is the core
 * interface. The default implementation ({@code SpringContextFailoverScanner} in the
 * {@code failover-scanner} module) locates all methods annotated with
 * {@link com.societegenerale.failover.annotations.Failover} by walking the Spring
 * {@code ApplicationContext} — no classpath scanning or external libraries required.
 */
package com.societegenerale.failover.core.scanner;
