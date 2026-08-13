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
 * Observable publisher SPI and built-in implementations.
 *
 * <p>{@link com.societegenerale.failover.core.observable.publisher.ObservablePublisher} is the
 * core SPI for publishing failover metrics to external sinks.
 * {@link com.societegenerale.failover.core.observable.publisher.AbstractObservablePublisher} provides
 * a base implementation. Default built-in publisher:
 * {@link com.societegenerale.failover.core.observable.publisher.MdcLoggerObservablePublisher}
 * (enriches MDC with metric attributes and logs via SLF4J).
 */
package com.societegenerale.failover.core.observable.publisher;
