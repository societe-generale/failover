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
 * Resilience4j integration for failover method execution.
 *
 * <p>{@link com.societegenerale.failover.execution.resilience.ResilienceFailoverExecution}
 * wraps the underlying method call in a Resilience4j
 * {@code TimeLimiter} / {@code CircuitBreaker} / {@code Retry} chain,
 * so that transient failures are handled before the failover recovery path is triggered.
 */
package com.societegenerale.failover.execution.resilience;
