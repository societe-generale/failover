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

package com.societegenerale.failover.dashboard.dto;

/**
 * Derived rate KPIs for a failover point (design doc §4.4). All values are fractions in {@code [0,1]};
 * {@code 0} (never {@code NaN}) when the denominator is zero.
 *
 * @param successRate     {@code S / Total} — upstream healthy, live value stored
 * @param failoverRate    {@code F / Total} — upstream failed, failover flow started
 * @param recoveryRate    {@code recovered / F} — failover served a stored, non-expired value
 * @param nonRecoveryRate {@code (notRecovered + errors) / F} — failover found nothing usable
 * @param healthyRate     {@code (S + recovered) / Total} — caller got a usable result (live or recovered)
 */
public record Rates(
        double successRate,
        double failoverRate,
        double recoveryRate,
        double nonRecoveryRate,
        double healthyRate) {
}
