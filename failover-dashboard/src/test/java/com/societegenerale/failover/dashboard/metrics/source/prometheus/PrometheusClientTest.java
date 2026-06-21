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
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient.RangeSeries;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient.Sample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PrometheusClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://prom");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final PrometheusClient client = new PrometheusClient(builder.build());

    @Test
    @DisplayName("query() parses a vector result into label/value samples")
    void parsesVector() {
        String json = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"name":"country","outcome":"recovered"},"value":[1700000000,"42"]},
                  {"metric":{"name":"fx"},"value":[1700000000,"7"]}
                ]}}""";
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<Sample> samples = client.query("sum by (name) (failover_store_total)");

        assertThat(samples).hasSize(2);
        assertThat(samples.getFirst().label("name")).isEqualTo("country");
        assertThat(samples.getFirst().label("outcome")).isEqualTo("recovered");
        assertThat(samples.getFirst().value()).isEqualTo(42.0);
        assertThat(samples.get(1).label("name")).isEqualTo("fx");
        assertThat(samples.get(1).value()).isEqualTo(7.0);
        server.verify();
    }

    @Test
    @DisplayName("query() throws when Prometheus reports a non-success status")
    void nonSuccessStatusThrows() {
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withSuccess("{\"status\":\"error\",\"errorType\":\"bad_data\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.query("bad"))
                .isInstanceOf(PrometheusException.class)
                .hasMessageContaining("status 'error'");
    }

    @Test
    @DisplayName("query() throws on an unparseable body")
    void unparseableBodyThrows() {
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.query("x"))
                .isInstanceOf(PrometheusException.class)
                .hasMessageContaining("parse");
    }

    @Test
    @DisplayName("query() throws on a transport / HTTP error")
    void transportErrorThrows() {
        server.expect(requestTo(containsString("/api/v1/query"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.query("x"))
                .isInstanceOf(PrometheusException.class)
                .hasMessageContaining("query failed");
    }

    @Test
    @DisplayName("query() coerces a missing/!=2 value array to 0.0")
    void vectorMalformedValueIsZero() {
        String json = """
                {"status":"success","data":{"result":[
                  {"metric":{"name":"country"},"value":[1700000000]},
                  {"metric":{"name":"fx"}}
                ]}}""";
        server.expect(requestTo(containsString("/api/v1/query")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<Sample> samples = client.query("x");

        assertThat(samples).hasSize(2);
        assertThat(samples.getFirst().value()).isZero();
        assertThat(samples.get(1).value()).isZero();
    }

    @Test
    @DisplayName("queryRange() parses a matrix result into time-ordered series")
    void parsesMatrix() {
        String json = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"name":"country"},"values":[[1700000000,"10"],[1700000030,"15"]]},
                  {"metric":{},"values":[[1700000000,"1"]]}
                ]}}""";
        server.expect(requestTo(containsString("/api/v1/query_range")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<RangeSeries> series = client.queryRange("sum(failover_store_total)", 1700000000L, 1700000030L, 30L);

        assertThat(series).hasSize(2);
        assertThat(series.getFirst().label("name")).isEqualTo("country");
        assertThat(series.getFirst().points()).hasSize(2);
        assertThat(series.getFirst().points().getFirst().timestampMs()).isEqualTo(1_700_000_000_000L); // *1000
        assertThat(series.getFirst().points().getFirst().value()).isEqualTo(10.0);
        server.verify();
    }

    @Test
    @DisplayName("queryRange() skips malformed (non-2-element) point arrays")
    void matrixSkipsMalformedPoints() {
        String json = """
                {"status":"success","data":{"result":[
                  {"metric":{"name":"country"},"values":[[1700000000,"10"],[1700000030],"nope"]}
                ]}}""";
        server.expect(requestTo(containsString("/api/v1/query_range")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<RangeSeries> series = client.queryRange("x", 0, 1, 1);

        assertThat(series).singleElement()
                .satisfies(s -> assertThat(s.points()).singleElement()
                        .satisfies(p -> assertThat(p.value()).isEqualTo(10.0)));
    }

    @Test
    @DisplayName("queryRange() throws on non-success status")
    void rangeNonSuccessThrows() {
        server.expect(requestTo(containsString("/api/v1/query_range")))
                .andRespond(withSuccess("{\"status\":\"error\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.queryRange("x", 0, 1, 1))
                .isInstanceOf(PrometheusException.class)
                .hasMessageContaining("range query");
    }

    @Test
    @DisplayName("queryRange() throws on an unparseable body")
    void rangeUnparseableThrows() {
        server.expect(requestTo(containsString("/api/v1/query_range")))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.queryRange("x", 0, 1, 1))
                .isInstanceOf(PrometheusException.class)
                .hasMessageContaining("parse");
    }

    @Test
    @DisplayName("queryRange() throws on a transport / HTTP error")
    void rangeTransportErrorThrows() {
        server.expect(requestTo(containsString("/api/v1/query_range"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.queryRange("x", 0, 1, 1))
                .isInstanceOf(PrometheusException.class)
                .hasMessageContaining("range query failed");
    }

    @Test
    @DisplayName("create() builds a client with and without a bearer token")
    void createBuildsClient() {
        assertThat(PrometheusClient.create(new DashboardProperties.Prometheus("http://x", "tok", 5))).isNotNull();
        assertThat(PrometheusClient.create(new DashboardProperties.Prometheus("http://x", "", 0))).isNotNull();
    }
}
