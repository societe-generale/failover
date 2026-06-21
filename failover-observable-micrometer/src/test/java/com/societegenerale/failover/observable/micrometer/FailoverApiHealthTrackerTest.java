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

package com.societegenerale.failover.observable.micrometer;

import com.societegenerale.failover.observable.micrometer.FailoverApiHealthTracker.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FailoverApiHealthTrackerTest {

    @Test
    void empty_history_is_healthy_and_not_stale() {
        FailoverApiHealthTracker tracker = new FailoverApiHealthTracker();

        assertThat(tracker.healthRatio("unseen")).isEqualTo(1.0);
        assertThat(tracker.staleRatio("unseen")).isEqualTo(0.0);
    }

    @Test
    void health_is_fresh_plus_stale_over_total_and_stale_is_stale_over_total() {
        FailoverApiHealthTracker tracker = new FailoverApiHealthTracker();
        tracker.record("api", Outcome.SERVED_FRESH);
        tracker.record("api", Outcome.SERVED_STALE);
        tracker.record("api", Outcome.BLOCKED);

        assertThat(tracker.healthRatio("api")).isCloseTo(2.0 / 3, within(1e-9)); // fresh+stale over 3
        assertThat(tracker.staleRatio("api")).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    void all_blocked_is_zero_health() {
        FailoverApiHealthTracker tracker = new FailoverApiHealthTracker();
        tracker.record("api", Outcome.BLOCKED);
        tracker.record("api", Outcome.BLOCKED);

        assertThat(tracker.healthRatio("api")).isEqualTo(0.0);
        assertThat(tracker.staleRatio("api")).isEqualTo(0.0);
    }

    @Test
    void window_evicts_oldest_so_health_reflects_only_recent_outcomes() {
        FailoverApiHealthTracker tracker = new FailoverApiHealthTracker(2);
        tracker.record("api", Outcome.BLOCKED);       // evicted
        tracker.record("api", Outcome.SERVED_FRESH);
        tracker.record("api", Outcome.SERVED_FRESH);  // window now [fresh, fresh]

        assertThat(tracker.healthRatio("api")).isEqualTo(1.0);
    }

    @Test
    void names_are_tracked_independently() {
        FailoverApiHealthTracker tracker = new FailoverApiHealthTracker();
        tracker.record("a", Outcome.SERVED_FRESH);
        tracker.record("b", Outcome.BLOCKED);

        assertThat(tracker.healthRatio("a")).isEqualTo(1.0);
        assertThat(tracker.healthRatio("b")).isEqualTo(0.0);
    }

    @Test
    void rejects_non_positive_window() {
        assertThatThrownBy(() -> new FailoverApiHealthTracker(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window must be > 0");
    }

    @Test
    void eviction_decrements_each_outcome_type_so_ratios_stay_correct() {
        FailoverApiHealthTracker tracker = new FailoverApiHealthTracker(3);
        // Fill, then push 3 more so each original (fresh, stale, blocked) is evicted in turn.
        tracker.record("api", Outcome.SERVED_FRESH);   // evicted by 4th
        tracker.record("api", Outcome.SERVED_STALE);   // evicted by 5th
        tracker.record("api", Outcome.BLOCKED);        // evicted by 6th
        tracker.record("api", Outcome.SERVED_STALE);
        tracker.record("api", Outcome.SERVED_STALE);
        tracker.record("api", Outcome.SERVED_STALE);   // window now [stale, stale, stale]

        assertThat(tracker.healthRatio("api")).isEqualTo(1.0);   // all served (stale counts as served)
        assertThat(tracker.staleRatio("api")).isEqualTo(1.0);    // all stale
    }
}
