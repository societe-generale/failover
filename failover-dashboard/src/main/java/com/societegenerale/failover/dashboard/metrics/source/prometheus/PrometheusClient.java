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

import com.societegenerale.failover.dashboard.config.DashboardProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin, read-only client for the Prometheus HTTP API ({@code GET /api/v1/query}). Used by
 * {@link PrometheusMetricsSource} to aggregate the {@code failover.*} meters across all instances.
 *
 * <p>Stateless and side-effect-free: it only issues instant queries and parses the {@code vector} result
 * into label/value {@link Sample}s. Any transport, HTTP-status, or parse failure surfaces as a
 * {@link PrometheusException} so the caller can fall back to the local registry.
 *
 * @author Anand Manissery
 */
public class PrometheusClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;

    PrometheusClient(RestClient http) {
        this.http = http;
    }

    /** Builds a client from the configured base URL, optional bearer token, and timeout. */
    public static PrometheusClient create(DashboardProperties.Prometheus cfg) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, cfg.timeoutSeconds()));
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory);
        if (cfg.token() != null && !cfg.token().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.token());
        }
        return new PrometheusClient(builder.build());
    }

    /**
     * Runs an instant PromQL query and returns the {@code vector} result as label/value samples.
     *
     * @throws PrometheusException on any transport, non-2xx, non-success, or parse failure
     */
    List<Sample> query(String promql) {
        String body;
        try {
            body = http.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/query").queryParam("query", promql).build())
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw new PrometheusException("Prometheus query failed: " + promql, e);
        }
        return parseVector(body, promql);
    }

    private static List<Sample> parseVector(String body, String promql) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!"success".equals(root.path("status").asText())) {
                throw new PrometheusException("Prometheus returned status '"
                        + root.path("status").asText() + "' for query: " + promql, null);
            }
            List<Sample> out = new ArrayList<>();
            for (JsonNode result : root.path("data").path("result")) {
                Map<String, String> labels = new LinkedHashMap<>();
                result.path("metric").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
                JsonNode value = result.path("value");
                double v = value.isArray() && value.size() == 2 ? value.get(1).asDouble() : 0.0;
                out.add(new Sample(labels, v));
            }
            return out;
        } catch (PrometheusException e) {
            throw e;
        } catch (Exception e) {
            throw new PrometheusException("Could not parse Prometheus response for query: " + promql, e);
        }
    }

    /**
     * Runs a PromQL {@code query_range} and returns the {@code matrix} result: one {@link RangeSeries}
     * per label set, each carrying its time-ordered points. Used for the cluster-wide trend chart.
     *
     * @throws PrometheusException on any transport, non-2xx, non-success, or parse failure
     */
    List<RangeSeries> queryRange(String promql, long startSec, long endSec, long stepSec) {
        String body;
        try {
            body = http.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/query_range")
                            .queryParam("query", promql)
                            .queryParam("start", startSec)
                            .queryParam("end", endSec)
                            .queryParam("step", stepSec + "s")
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw new PrometheusException("Prometheus range query failed: " + promql, e);
        }
        return parseMatrix(body, promql);
    }

    private static List<RangeSeries> parseMatrix(String body, String promql) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!"success".equals(root.path("status").asText())) {
                throw new PrometheusException("Prometheus returned status '"
                        + root.path("status").asText() + "' for range query: " + promql, null);
            }
            List<RangeSeries> out = new ArrayList<>();
            for (JsonNode result : root.path("data").path("result")) {
                Map<String, String> labels = new LinkedHashMap<>();
                result.path("metric").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
                List<RangePoint> points = new ArrayList<>();
                for (JsonNode value : result.path("values")) {
                    if (value.isArray() && value.size() == 2) {
                        points.add(new RangePoint((long) (value.get(0).asDouble() * 1000.0), value.get(1).asDouble()));
                    }
                }
                out.add(new RangeSeries(labels, points));
            }
            return out;
        } catch (PrometheusException e) {
            throw e;
        } catch (Exception e) {
            throw new PrometheusException("Could not parse Prometheus range response for query: " + promql, e);
        }
    }

    /** One Prometheus vector element: its label set and instantaneous value. */
    record Sample(Map<String, String> labels, double value) {
        String label(String key) {
            return labels.get(key);
        }
    }

    /** One {@code query_range} series: its label set and time-ordered points. */
    record RangeSeries(Map<String, String> labels, List<RangePoint> points) {
        String label(String key) {
            return labels.get(key);
        }
    }

    /** One sample within a {@link RangeSeries}: timestamp (epoch millis) and value. */
    record RangePoint(long timestampMs, double value) {
    }
}

