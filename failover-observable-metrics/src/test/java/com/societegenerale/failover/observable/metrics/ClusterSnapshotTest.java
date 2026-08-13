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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterSnapshotTest {

    @Test
    @DisplayName("2-arg constructor (older peers, or callers with no config payload) normalises to an empty list")
    void twoArgConstructorNormalisesToEmptyList() {
        ClusterSnapshot snapshot = new ClusterSnapshot("instance-1", null);

        assertThat(snapshot.configEntries()).isEmpty();
    }

    @Test
    @DisplayName("an explicit null configEntries (e.g. deserializing an older stored row) normalises to an empty list")
    void nullConfigEntriesNormalisesToEmptyList() {
        ClusterSnapshot snapshot = new ClusterSnapshot("instance-1", null, null);

        assertThat(snapshot.configEntries()).isEmpty();
    }

    @Test
    @DisplayName("Jackson deserializes a legacy peer payload (no 'configEntries' field) into an empty list, not a failure")
    void deserializesLegacyJsonMissingConfigEntriesField() throws Exception {
        // Exactly what ClusterSnapshotController's @RequestBody sees from a peer running the pre-config-entries
        // failover-observable-micrometer jar: only instanceId + summary, no configEntries key at all.
        String legacyJson = """
                {"instanceId":"peer-1","summary":{"overall":null,"perApi":[],"topExceptions":[],"timestamp":0}}
                """;

        ClusterSnapshot snapshot = new ObjectMapper().readValue(legacyJson, ClusterSnapshot.class);

        assertThat(snapshot.instanceId()).isEqualTo("peer-1");
        assertThat(snapshot.configEntries()).isEmpty();
    }

    @Test
    @DisplayName("Jackson round-trips a snapshot with config entries")
    void roundTripsWithConfigEntries() throws Exception {
        ConfigEntry entry = new ConfigEntry("alpha", "alpha", 1L, "HOURS", false,
                "default", "default", "default", "inmemory", "basic", "rethrow", true);
        ClusterSnapshot original = new ClusterSnapshot("peer-1",
                new MetricsSummary(null, java.util.List.of(), java.util.List.of(), 0L), java.util.List.of(entry));
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(original);
        ClusterSnapshot roundTripped = mapper.readValue(json, ClusterSnapshot.class);

        assertThat(roundTripped.configEntries()).containsExactly(entry);
    }
}
