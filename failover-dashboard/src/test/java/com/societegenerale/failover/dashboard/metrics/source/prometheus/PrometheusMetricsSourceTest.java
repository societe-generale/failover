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

package com.societegenerale.failover.dashboard.metrics.source.prometheus;

import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.dashboard.config.DashboardProperties;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient.RangePoint;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient.RangeSeries;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient.Sample;
import com.societegenerale.failover.dashboard.metrics.ApiHealth;
import com.societegenerale.failover.dashboard.metrics.ApiKpis;
import com.societegenerale.failover.dashboard.metrics.InstanceMetrics;
import com.societegenerale.failover.dashboard.metrics.MetricsSummary;
import com.societegenerale.failover.dashboard.metrics.SeriesPoint;
import com.societegenerale.failover.dashboard.metrics.SourceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrometheusMetricsSourceTest {

    private final PrometheusClient client = mock(PrometheusClient.class);
    private final MetricsSource fallback = mock(MetricsSource.class);
    private final DashboardProperties.Health thresholds = new DashboardProperties.Health(0.99, 0.90);
    private final PrometheusMetricsSource source = new PrometheusMetricsSource(client, fallback, thresholds);

    private static Sample sample(double value, String... kv) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            labels.put(kv[i], kv[i + 1]);
        }
        return new Sample(labels, value);
    }

    /** Stub the Prometheus queries with a two-API cluster: country (degraded) and fx (healthy). */
    @BeforeEach
    void stubCluster() {
        when(client.query(argThat(q -> q != null && q.contains("stored=")))).thenReturn(List.of(
                sample(90, "name", "country"), sample(80, "name", "fx")));
        when(client.query(argThat(q -> q != null && q.contains("recovery_outcome_total")))).thenReturn(List.of(
                sample(8, "name", "country", "domain", "geo", "outcome", "recovered"),
                sample(1, "name", "country", "domain", "geo", "outcome", "not_recovered"),
                sample(1, "name", "country", "domain", "geo", "outcome", "error"),
                sample(4, "name", "fx", "domain", "fx", "outcome", "recovered")));
        when(client.query(argThat(q -> q != null && q.contains("recovery_partial_total")))).thenReturn(List.of(
                sample(2, "name", "country")));
        when(client.query(argThat(q -> q != null && q.contains("async_failed")))).thenReturn(List.of());
        when(client.query(argThat(q -> q != null && q.contains("duration_seconds_sum")))).thenReturn(List.of(
                sample(0.15, "name", "country", "action", "store"),
                sample(0.22, "name", "country", "action", "recover")));
        when(client.query(argThat(q -> q != null && q.contains("duration_seconds_count")))).thenReturn(List.of(
                sample(100, "name", "country", "action", "store"),
                sample(100, "name", "country", "action", "recover")));
        when(client.query(argThat(q -> q != null && q.contains("duration_seconds_max")))).thenReturn(List.of(
                sample(0.004, "name", "country", "action", "store"),
                sample(0.009, "name", "country", "action", "recover")));
        when(client.query(argThat(q -> q != null && q.contains("histogram_quantile(0.95")))).thenReturn(List.of(
                sample(0.05, "name", "country", "action", "recover")));   // p95 = 50ms
        when(client.query(argThat(q -> q != null && q.contains("histogram_quantile(0.99")))).thenReturn(List.of(
                sample(0.12, "name", "country", "action", "recover")));   // p99 = 120ms
        when(client.query(argThat(q -> q != null && q.contains("exception_total")))).thenReturn(List.of(
                sample(7, "final_cause_type", "java.net.SocketTimeoutException", "cause_type", "none", "exception_type", "x")));
        when(client.query(argThat(q -> q != null && q.contains("group by (instance)")))).thenReturn(List.of(sample(3)));
    }

    @Test
    @DisplayName("summary() aggregates per-API + overall KPIs across instances")
    void summaryAggregates() {
        MetricsSummary summary = source.summary();

        assertThat(summary.perApi()).extracting(ApiKpis::name).containsExactly("country", "fx");
        ApiKpis country = summary.perApi().getFirst();
        assertThat(country.totalCalls()).isEqualTo(100);     // 90 success + (8+1+1) failover
        assertThat(country.failoverInvoked()).isEqualTo(10);
        assertThat(country.recovered()).isEqualTo(8);
        assertThat(country.partial()).isEqualTo(2);
        assertThat(country.domain()).isEqualTo("geo");
        assertThat(country.latency().recoverMeanMs()).isEqualTo(2.2);   // 0.22s / 100 * 1000
        assertThat(country.latency().recoverP95Ms()).isEqualTo(50.0);   // histogram_quantile 0.95 → 0.05s
        assertThat(country.latency().recoverP99Ms()).isEqualTo(120.0);  // histogram_quantile 0.99 → 0.12s
        assertThat(country.rates().healthyRate()).isCloseTo(0.98, within(1e-9));

        assertThat(summary.overall().totalCalls()).isEqualTo(184);      // 100 + 84
        assertThat(summary.topExceptions()).singleElement()
                .satisfies(e -> {
                    assertThat(e.type()).isEqualTo("java.net.SocketTimeoutException");
                    assertThat(e.count()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("health() classifies each API against the thresholds")
    void healthClassifies() {
        List<ApiHealth> health = source.health();

        assertThat(health).extracting(ApiHealth::name, ApiHealth::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("country", "DEGRADED"),   // 0.98 in [0.90, 0.99)
                        org.assertj.core.groups.Tuple.tuple("fx", "HEALTHY"));        // 0.999 >= 0.99
    }

    @Test
    @DisplayName("info() reports prometheus provenance with the cluster instance count")
    void infoReportsCluster() {
        SourceInfo info = source.info();

        assertThat(info.mode()).isEqualTo("prometheus");
        assertThat(info.instancesReporting()).isEqualTo(3);
        assertThat(info.instancesExpected()).isEqualTo(-1);
        assertThat(info.partial()).isFalse();
    }

    @Test
    @DisplayName("instances() returns one summary per instance, grouped by the instance label")
    void instancesPerInstance() {
        // per-instance variants keep the 'instance' label; stubbed after @BeforeEach so these win for the
        // by-instance queries (matched on '(name, instance)' / ', instance,').
        when(client.query(argThat(q -> q != null && q.contains("(name, instance)") && q.contains("stored="))))
                .thenReturn(List.of(
                        sample(100, "name", "country", "instance", "host-1"),
                        sample(50, "name", "country", "instance", "host-2")));
        when(client.query(argThat(q -> q != null && q.contains("name, domain, instance, outcome"))))
                .thenReturn(List.of(
                        sample(2, "name", "country", "domain", "geo", "instance", "host-1", "outcome", "recovered"),
                        sample(8, "name", "country", "domain", "geo", "instance", "host-2", "outcome", "not_recovered")));

        List<InstanceMetrics> instances = source.instances();

        assertThat(instances).extracting(InstanceMetrics::instanceId).containsExactly("host-1", "host-2");
        InstanceMetrics h1 = instances.getFirst();
        assertThat(h1.summary().overall().totalCalls()).isEqualTo(102);   // 100 success + 2 recovered
        assertThat(h1.summary().overall().recovered()).isEqualTo(2);
        InstanceMetrics h2 = instances.get(1);
        assertThat(h2.summary().overall().totalCalls()).isEqualTo(58);    // 50 success + 8 not_recovered
        assertThat(h2.summary().overall().notRecovered()).isEqualTo(8);
    }

    @Test
    @DisplayName("instances() discovers an instance present only in the outcome query (name union)")
    void instancesUnionFromOutcomes() {
        when(client.query(argThat(q -> q != null && q.contains("(name, instance)") && q.contains("stored="))))
                .thenReturn(List.of(sample(100, "name", "country", "instance", "host-1")));
        when(client.query(argThat(q -> q != null && q.contains("name, domain, instance, outcome"))))
                .thenReturn(List.of(
                        sample(2, "name", "country", "domain", "geo", "instance", "host-1", "outcome", "recovered"),
                        // host-2 has NO success sample — only an outcome → discovered via the name union
                        sample(9, "name", "fx", "domain", "fx", "instance", "host-2", "outcome", "not_recovered")));

        assertThat(source.instances()).extracting(InstanceMetrics::instanceId)
                .containsExactlyInAnyOrder("host-1", "host-2");
    }

    @Test
    @DisplayName("instances() returns empty (tab hidden) when Prometheus per-instance query fails")
    void instancesFallsBackEmpty() {
        when(client.query(argThat(q -> q != null && q.contains("(name, instance)") && q.contains("stored="))))
                .thenThrow(new PrometheusException("boom", null));

        assertThat(source.instances()).isEmpty();
    }

    @Test
    @DisplayName("info() reports zero instances when the instance probe returns an empty vector")
    void infoZeroInstancesWhenEmpty() {
        when(client.query(argThat(q -> q != null && q.contains("group by (instance)")))).thenReturn(List.of());

        assertThat(source.info().instancesReporting()).isZero();
    }

    @Test
    @DisplayName("summary() falls back to the local source when Prometheus fails")
    void summaryFallsBack() {
        when(client.query(argThat(q -> q != null && q.contains("stored="))))
                .thenThrow(new PrometheusException("boom", null));
        MetricsSummary fallbackSummary = new MetricsSummary(null, List.of(), List.of(), 1L);
        when(fallback.summary()).thenReturn(fallbackSummary);

        assertThat(source.summary()).isSameAs(fallbackSummary);
    }

    @Test
    @DisplayName("info() falls back to the local source when the instance probe fails")
    void infoFallsBack() {
        when(client.query(argThat(q -> q != null && q.contains("group by (instance)"))))
                .thenThrow(new PrometheusException("boom", null));
        SourceInfo localInfo = new SourceInfo("local", 1, -1, 1L, false);
        when(fallback.info()).thenReturn(localInfo);

        assertThat(source.info()).isSameAs(localInfo);
    }

    private static RangeSeries range(double... tsValuePairs) {
        List<RangePoint> pts = new java.util.ArrayList<>();
        for (int i = 0; i < tsValuePairs.length; i += 2) {
            pts.add(new RangePoint((long) tsValuePairs[i], tsValuePairs[i + 1]));
        }
        return new RangeSeries(Map.of(), pts);
    }

    @Test
    @DisplayName("series() builds a cluster trend from query_range, including per-API failover")
    void seriesFromQueryRange() {
        when(client.queryRange(argThat(q -> q != null && q.contains("+ sum")), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(range(1000, 100, 2000, 130)));
        when(client.queryRange(eq("sum(failover_store_total{stored=\"true\"})"), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(range(1000, 90, 2000, 117)));
        when(client.queryRange(eq("sum(failover_recovery_outcome_total)"), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(range(1000, 10, 2000, 13)));
        when(client.queryRange(eq("sum(failover_recovery_outcome_total{outcome=\"recovered\"})"), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(range(1000, 8, 2000, 11)));
        when(client.queryRange(eq("sum(failover_recovery_outcome_total{outcome=\"not_recovered\"})"), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(range(1000, 1, 2000, 1)));
        when(client.queryRange(eq("sum by (name) (failover_recovery_outcome_total)"), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(new RangeSeries(Map.of("name", "country"),
                        List.of(new RangePoint(1000, 10), new RangePoint(2000, 13)))));

        List<SeriesPoint> series = source.series(0);

        assertThat(series).hasSize(2);
        SeriesPoint first = series.getFirst();
        assertThat(first.timestamp()).isEqualTo(1000);
        assertThat(first.calls()).isEqualTo(100);
        assertThat(first.failover()).isEqualTo(10);
        assertThat(first.recovered()).isEqualTo(8);
        assertThat(first.store()).isEqualTo(90);
        assertThat(first.failoverByApi()).containsEntry("country", 10L);
        assertThat(series.get(1).calls()).isEqualTo(130);
    }

    @Test
    @DisplayName("series() builds its time spine from failover when the calls series is empty")
    void seriesSpineFromFailoverWhenCallsEmpty() {
        // R_CALLS ('+ sum') empty → spine falls back to the failover series' timestamps; scalarRange empty paths.
        when(client.queryRange(argThat(q -> q != null && q.contains("+ sum")), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of());
        when(client.queryRange(eq("sum(failover_recovery_outcome_total)"), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(range(1000, 5, 2000, 9)));
        // a by-API series with NO 'name' label → skipped by byNameRange
        when(client.queryRange(eq("sum by (name) (failover_recovery_outcome_total)"), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(new RangeSeries(Map.of(), List.of(new RangePoint(1000, 5)))));

        List<SeriesPoint> series = source.series(120);

        assertThat(series).hasSize(2);
        assertThat(series.getFirst().failover()).isEqualTo(5);
        assertThat(series.getFirst().calls()).isZero();              // calls series was empty
        assertThat(series.getFirst().failoverByApi()).isEmpty();     // unnamed by-API series skipped
    }

    @Test
    @DisplayName("instances() drops samples lacking an instance label")
    void instancesDropUnlabelled() {
        when(client.query(argThat(q -> q != null && q.contains("(name, instance)") && q.contains("stored="))))
                .thenReturn(List.of(
                        sample(100, "name", "country", "instance", "host-1"),
                        sample(7, "name", "country")));   // no instance label → dropped

        assertThat(source.instances()).extracting(InstanceMetrics::instanceId).containsExactly("host-1");
    }

    @Test
    @DisplayName("series() falls back to the local source when query_range fails")
    void seriesFallsBack() {
        when(client.queryRange(argThat(q -> q != null), anyLong(), anyLong(), anyLong()))
                .thenThrow(new PrometheusException("boom", null));
        List<SeriesPoint> local = List.of(new SeriesPoint(1L, 1, 0, 0, 0, 1, 0, Map.of()));
        when(fallback.series(0)).thenReturn(local);

        assertThat(source.series(0)).isSameAs(local);
    }
}
