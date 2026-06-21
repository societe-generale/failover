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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling per-API health state, fed by every store/recover event and read by the {@code failover.api.health}
 * and {@code failover.stale.served.ratio} gauges. Each failover name keeps a fixed-size ring of its most
 * recent outcomes (default {@value #DEFAULT_WINDOW}) so the gauges reflect <em>recent</em> behaviour — a live
 * "guard" — rather than a lifetime average that never recovers after a bad spell.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>{@link Outcome#SERVED_FRESH} — upstream succeeded (the caller got a fresh value);</li>
 *   <li>{@link Outcome#SERVED_STALE} — upstream failed but a stored value was recovered (caller unblocked, stale);</li>
 *   <li>{@link Outcome#BLOCKED} — upstream failed and nothing could be recovered (caller blocked).</li>
 * </ul>
 *
 * <p>{@code healthRatio = (fresh + stale) / total} (1.0 = every caller got a value) and
 * {@code staleRatio = stale / total}. Empty history reports {@code health=1.0}, {@code stale=0.0} (never NaN).
 *
 * @author Anand Manissery
 */
class FailoverApiHealthTracker {

    /** Default number of recent outcomes retained per failover name. */
    static final int DEFAULT_WINDOW = 200;

    enum Outcome { SERVED_FRESH, SERVED_STALE, BLOCKED }

    private final int window;
    private final Map<String, Window> byName = new ConcurrentHashMap<>();

    FailoverApiHealthTracker() {
        this(DEFAULT_WINDOW);
    }

    FailoverApiHealthTracker(int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window must be > 0, but was " + window);
        }
        this.window = window;
    }

    void record(String name, Outcome outcome) {
        byName.computeIfAbsent(name, n -> new Window(window)).record(outcome);
    }

    /** Fraction of recent calls where the caller got a value (fresh or recovered). 1.0 when no history. */
    double healthRatio(String name) {
        Window w = byName.get(name);
        return w == null ? 1.0 : w.healthRatio();
    }

    /** Fraction of recent calls served from stored (stale) data. 0.0 when no history. */
    double staleRatio(String name) {
        Window w = byName.get(name);
        return w == null ? 0.0 : w.staleRatio();
    }

    /** Fixed-size ring with running tallies; guarded by its own monitor (writes are cheap, reads infrequent). */
    private static final class Window {
        private final Outcome[] ring;
        private int head;
        private int size;
        private int fresh;
        private int stale;
        private int blocked;

        Window(int capacity) {
            this.ring = new Outcome[capacity];
        }

        synchronized void record(Outcome outcome) {
            if (size == ring.length) {
                decrement(ring[head]);                 // evict oldest
                ring[head] = outcome;
                head = (head + 1) % ring.length;
            } else {
                ring[(head + size) % ring.length] = outcome;
                size++;
            }
            increment(outcome);
        }

        synchronized double healthRatio() {
            return size == 0 ? 1.0 : (double) (fresh + stale) / size;
        }

        synchronized double staleRatio() {
            return size == 0 ? 0.0 : (double) stale / size;
        }

        private void increment(Outcome o) {
            switch (o) {
                case SERVED_FRESH -> fresh++;
                case SERVED_STALE -> stale++;
                case BLOCKED -> blocked++;
            }
        }

        private void decrement(Outcome o) {
            switch (o) {
                case SERVED_FRESH -> fresh--;
                case SERVED_STALE -> stale--;
                case BLOCKED -> blocked--;
            }
        }
    }
}
