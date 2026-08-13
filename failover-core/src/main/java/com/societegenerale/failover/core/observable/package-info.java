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
 * Observable SPI and core implementations for the failover lifecycle.
 *
 * <p>{@link com.societegenerale.failover.core.observable.FailoverObserver} collects metrics
 * from all registered {@code @Failover} configurations and fans them out to
 * {@link com.societegenerale.failover.core.observable.publisher.ObservablePublisher} instances.
 * {@link com.societegenerale.failover.core.observable.publisher.CompositeObservablePublisher}
 * stamps a single publish timestamp and delegates to all registered publishers simultaneously.
 */
package com.societegenerale.failover.core.observable;
