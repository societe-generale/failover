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

package com.societegenerale.failover.observable.metrics;

/**
 * Per-API health classification derived from {@link Rates#healthyRate()} against configurable
 * thresholds (design doc §4.4).
 *
 * @param name        failover name
 * @param status      {@code HEALTHY} | {@code DEGRADED} | {@code UNHEALTHY}
 * @param healthyRate the {@code (S + recovered) / Total} rate the status was derived from
 */
public record ApiHealth(
        String name,
        String status,
        double healthyRate) {

    /** Health status values. */
    public enum Status { HEALTHY, DEGRADED, UNHEALTHY }
}
