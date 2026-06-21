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

package com.societegenerale.failover.dashboard.metrics.source.sharedstore;

import com.societegenerale.failover.dashboard.metrics.SeriesPoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Bounded, time-ordered ring of cluster-wide trend points for {@code cluster.mode=shared-store}, pruned by a
 * {@link RetentionPolicy} (age + size, oldest first). Fed by {@link ClusterSeriesSampler}; read by
 * {@code SharedStoreMetricsSource.series(...)}. In-memory — bounded trend history, not a TSDB.
 *
 * @author Anand Manissery
 */
public class ClusterSeriesStore {

    private final RetentionPolicy retention;
    private final LongSupplier nowMillis;
    private final Deque<SeriesPoint> ring = new ArrayDeque<>();

    public ClusterSeriesStore(RetentionPolicy retention) {
        this(retention, System::currentTimeMillis);
    }

    /** Test seam: inject a clock. */
    ClusterSeriesStore(RetentionPolicy retention, LongSupplier nowMillis) {
        this.retention = retention;
        this.nowMillis = nowMillis;
    }

    /** Appends a point (assumed newest) and prunes by size then age. */
    public void append(SeriesPoint point) {
        synchronized (ring) {
            ring.addLast(point);
            long now = nowMillis.getAsLong();
            while (ring.size() > retention.maxEntries()) {
                ring.removeFirst();
            }
            while (!ring.isEmpty() && retention.isExpired(ring.peekFirst().timestamp(), now)) {
                ring.removeFirst();
            }
        }
    }

    /**
     * @param windowSec only points captured within this many seconds of now ({@code <= 0} returns all retained)
     * @return retained points in chronological order
     */
    public List<SeriesPoint> series(long windowSec) {
        long floor = windowSec > 0 ? nowMillis.getAsLong() - windowSec * 1000 : Long.MIN_VALUE;
        synchronized (ring) {
            List<SeriesPoint> out = new ArrayList<>(ring.size());
            for (SeriesPoint p : ring) {
                if (p.timestamp() >= floor) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    /** Current number of retained points (diagnostics / tests). */
    public int size() {
        synchronized (ring) {
            return ring.size();
        }
    }
}
