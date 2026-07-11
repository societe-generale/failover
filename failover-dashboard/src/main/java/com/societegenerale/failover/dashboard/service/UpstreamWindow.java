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

package com.societegenerale.failover.dashboard.service;

/**
 * A failover point's rolling last-{@code sampleSize}-calls rates (design doc, health §4.4 addendum),
 * as opposed to the lifetime-cumulative rates in {@link com.societegenerale.failover.observable.metrics.Rates}.
 * Powers the dashboard's Upstream call health cards, which are deliberately scored on the upstream call
 * alone and must reflect a recovered upstream promptly rather than staying dragged down by an old spell
 * of failures.
 *
 * @param failoverRate  fraction of the windowed calls where the upstream call itself failed
 * @param healthyRate   fraction of the windowed calls where the caller got a usable value (fresh or recovered)
 * @param recoveryRate  of the windowed upstream failures, the fraction failover recovered a value for
 * @param sampleCount   how many calls the window currently holds (at most the configured sample size)
 * @author Anand Manissery
 */
public record UpstreamWindow(double failoverRate, double healthyRate, double recoveryRate, int sampleCount) {
}
