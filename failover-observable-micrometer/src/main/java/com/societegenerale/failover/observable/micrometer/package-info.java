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
 * Micrometer-backed observability for the failover framework.
 *
 * <p>{@link com.societegenerale.failover.observable.micrometer.MicrometerObservablePublisher}
 * translates failover lifecycle events into Micrometer meters — store/recover counters, the
 * per-method recovery outcome metric, and the partial-recovery counter — recorded against the
 * application's {@code MeterRegistry}. The package also contributes a failover health indicator. Active
 * only when Micrometer is on the classpath.
 */
package com.societegenerale.failover.observable.micrometer;
