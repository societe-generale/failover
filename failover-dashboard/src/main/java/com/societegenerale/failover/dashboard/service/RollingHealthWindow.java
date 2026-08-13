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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling last-{@code sampleSize}-calls health window per failover point, reconstructed by polling
 * the (monotonic, lifetime-cumulative) {@code failover.*} counters and diffing against the previously
 * observed totals — no per-call event hook needed.
 *
 * <p>Fixes the "stuck DEGRADED" problem: a lifetime {@code healthyRate} never fully recovers from an
 * old bad spell (a handful of errors from hours ago keep dragging an endpoint that has been fine for
 * the last 10,000 calls into DEGRADED). This tracker instead ages old outcomes out once
 * {@code sampleSize} more-recent ones have arrived, so a recovered upstream reflects as recovered as
 * soon as enough fresh calls have gone through it.
 *
 * <p>Deltas are captured at per-poll (not per-call) granularity: each {@link #sample} call records how
 * many fresh/stale/blocked outcomes occurred since the <em>previous</em> sample for that name, as one
 * batch. Membership in the window is exact (no outcome is double-counted or dropped); only the relative
 * order of outcomes <em>within</em> a single poll interval is not preserved, which does not affect the
 * derived rates.
 *
 * @author Anand Manissery
 */
class RollingHealthWindow {

    private final int capacity;
    private final Map<String, long[]> lastSeen = new ConcurrentHashMap<>();   // name -> [fresh, stale, blocked] cumulative totals as of the last sample
    private final Map<String, Ring> rings = new ConcurrentHashMap<>();

    RollingHealthWindow(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, but was " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * Feeds the window with the current cumulative totals for {@code name} and returns the resulting
     * windowed rates.
     *
     * @param name    failover name
     * @param fresh   cumulative upstream-success count (caller got a fresh value)
     * @param stale   cumulative recovered count (caller got a stale, stored value)
     * @param blocked cumulative not-recovered + error count (caller got nothing usable)
     * @return the windowed rates after folding in this observation
     */
    WindowedRates sample(String name, long fresh, long stale, long blocked) {
        long[] previous = lastSeen.put(name, new long[]{fresh, stale, blocked});
        Ring ring = rings.computeIfAbsent(name, n -> new Ring(capacity));
        if (previous == null) {
            ring.add(fresh, stale, blocked);
        } else {
            ring.add(Math.max(0, fresh - previous[0]), Math.max(0, stale - previous[1]), Math.max(0, blocked - previous[2]));
        }
        return ring.rates();
    }

    /** Windowed healthy-rate, failover-rate and recovery-rate, plus how many calls the window currently holds. */
    record WindowedRates(double healthyRate, double failoverRate, double recoveryRate, int sampleCount) {
        static final WindowedRates EMPTY = new WindowedRates(1.0, 0.0, 0.0, 0);
    }

    /**
     * Fixed-capacity ring of (fresh, stale, blocked) poll-batches with running tallies. When a new batch
     * would push the total over capacity, the oldest batches are evicted — fully, or (for the batch that
     * straddles the boundary) proportionally trimmed — so the tallies always reflect exactly the most
     * recent {@code capacity} calls.
     */
    private static final class Ring {
        private final Deque<long[]> batches = new ArrayDeque<>();   // oldest first, each [fresh, stale, blocked]
        private final int capacity;
        private long fresh;
        private long stale;
        private long blocked;

        Ring(int capacity) {
            this.capacity = capacity;
        }

        synchronized void add(long f, long s, long b) {
            if (f == 0 && s == 0 && b == 0) {
                return;
            }
            batches.addLast(new long[]{f, s, b});
            fresh += f;
            stale += s;
            blocked += b;
            evictExcess();
        }

        private void evictExcess() {
            long total = fresh + stale + blocked;
            while (total > capacity && !batches.isEmpty()) {
                long[] oldest = batches.peekFirst();
                long oldestSize = oldest[0] + oldest[1] + oldest[2];
                long excess = total - capacity;
                if (oldestSize <= excess) {
                    batches.pollFirst();
                    fresh -= oldest[0];
                    stale -= oldest[1];
                    blocked -= oldest[2];
                    total -= oldestSize;
                } else {
                    // Trim the straddling batch proportionally — no finer-grained ordering is known within it.
                    double keep = (double) (oldestSize - excess) / oldestSize;
                    long trimmedFresh = Math.round(oldest[0] * keep);
                    long trimmedStale = Math.round(oldest[1] * keep);
                    long trimmedBlocked = Math.round(oldest[2] * keep);
                    fresh -= oldest[0] - trimmedFresh;
                    stale -= oldest[1] - trimmedStale;
                    blocked -= oldest[2] - trimmedBlocked;
                    oldest[0] = trimmedFresh;
                    oldest[1] = trimmedStale;
                    oldest[2] = trimmedBlocked;
                    total = fresh + stale + blocked;
                }
            }
        }

        synchronized WindowedRates rates() {
            long total = fresh + stale + blocked;
            if (total == 0) {
                return WindowedRates.EMPTY;
            }
            long failover = stale + blocked;
            double healthyRate = (double) (fresh + stale) / total;
            double failoverRate = (double) failover / total;
            double recoveryRate = failover == 0 ? 0.0 : (double) stale / failover;
            return new WindowedRates(healthyRate, failoverRate, recoveryRate, (int) total);
        }
    }
}
