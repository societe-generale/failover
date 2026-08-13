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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RollingHealthWindowTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("non-positive capacity is rejected")
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new RollingHealthWindow(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RollingHealthWindow(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("first observation seeds the window with the full lifetime totals, capped at capacity")
    void firstObservationSeedsWindow() {
        RollingHealthWindow window = new RollingHealthWindow(100);

        RollingHealthWindow.WindowedRates r = window.sample("svc", 90, 8, 2);

        assertThat(r.sampleCount()).isEqualTo(100);
        assertThat(r.healthyRate()).isEqualTo(0.98, within(EPS));
        assertThat(r.failoverRate()).isEqualTo(0.10, within(EPS));
        assertThat(r.recoveryRate()).isEqualTo(0.80, within(EPS));
    }

    @Test
    @DisplayName("only the delta since the previous sample is folded in, not the full cumulative total again")
    void onlyDeltaIsCounted() {
        RollingHealthWindow window = new RollingHealthWindow(1000);

        window.sample("svc", 50, 0, 0);
        RollingHealthWindow.WindowedRates r = window.sample("svc", 90, 0, 0);   // +40 fresh since last sample

        assertThat(r.sampleCount()).isEqualTo(90);
        assertThat(r.healthyRate()).isEqualTo(1.0, within(EPS));
    }

    @Test
    @DisplayName("a recovered upstream is reflected as soon as enough fresh calls age the old failures out of the window")
    void recoveryIsReflectedPromptlyOnceWindowFills() {
        RollingHealthWindow window = new RollingHealthWindow(10);

        // A bad spell: 10 calls, all blocked -> fully unhealthy window.
        RollingHealthWindow.WindowedRates bad = window.sample("svc", 0, 0, 10);
        assertThat(bad.healthyRate()).isZero();

        // Upstream fully recovers: 10 more calls, all fresh. The old 10 blocked calls must age out completely.
        RollingHealthWindow.WindowedRates recovered = window.sample("svc", 10, 0, 10);
        assertThat(recovered.sampleCount()).isEqualTo(10);
        assertThat(recovered.healthyRate()).isEqualTo(1.0, within(EPS));
        assertThat(recovered.failoverRate()).isZero();
    }

    @Test
    @DisplayName("a batch straddling the capacity boundary is proportionally trimmed, not fully evicted or fully kept")
    void straddlingBatchIsProportionallyTrimmed() {
        RollingHealthWindow window = new RollingHealthWindow(10);

        window.sample("svc", 8, 0, 0);                       // window: 8 fresh (size 8)
        RollingHealthWindow.WindowedRates r = window.sample("svc", 8, 0, 4); // +0 fresh, +4 blocked -> would be size 12, excess 2

        // The oldest batch (8 fresh) is trimmed by the 2-call excess: keeps 6 of the original 8 fresh calls.
        assertThat(r.sampleCount()).isEqualTo(10);
        assertThat(r.healthyRate()).isEqualTo(0.6, within(EPS));
        assertThat(r.failoverRate()).isEqualTo(0.4, within(EPS));
    }

    @Test
    @DisplayName("negative deltas (a counter that appears to shrink) are floored at zero, never counted negative")
    void negativeDeltaFlooredAtZero() {
        RollingHealthWindow window = new RollingHealthWindow(100);

        window.sample("svc", 50, 10, 5);
        // Cumulative totals should never decrease, but guard against it defensively.
        RollingHealthWindow.WindowedRates r = window.sample("svc", 40, 10, 5);

        assertThat(r.sampleCount()).isEqualTo(65);   // unchanged: zero delta folded in, nothing new added
    }

    @Test
    @DisplayName("distinct failover names are tracked independently")
    void namesAreIndependent() {
        RollingHealthWindow window = new RollingHealthWindow(100);

        window.sample("a", 100, 0, 0);
        window.sample("b", 0, 0, 100);

        assertThat(window.sample("a", 100, 0, 0).healthyRate()).isEqualTo(1.0, within(EPS));
        assertThat(window.sample("b", 0, 0, 100).healthyRate()).isZero();
    }

    @Test
    @DisplayName("no calls at all yields the EMPTY default: healthyRate 1.0, never NaN")
    void noCallsYieldsEmptyDefault() {
        RollingHealthWindow.WindowedRates empty = RollingHealthWindow.WindowedRates.EMPTY;

        assertThat(empty.healthyRate()).isEqualTo(1.0, within(EPS));
        assertThat(empty.failoverRate()).isZero();
        assertThat(empty.recoveryRate()).isZero();
        assertThat(empty.sampleCount()).isZero();
    }

    @Test
    @DisplayName("recoveryRate is 0 (never NaN) when the window holds only successes, no upstream failures")
    void recoveryRateZeroWhenNoFailover() {
        RollingHealthWindow window = new RollingHealthWindow(100);

        RollingHealthWindow.WindowedRates r = window.sample("svc", 50, 0, 0);

        assertThat(r.recoveryRate()).isZero();
    }
}
