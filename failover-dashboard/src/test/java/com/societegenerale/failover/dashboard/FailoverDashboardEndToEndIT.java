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

package com.societegenerale.failover.dashboard;

import com.societegenerale.failover.annotations.Failover;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test (design doc §13): a real {@code @Failover} bean over the full failover
 * runtime (aspect + autoconfigure + in-memory store + Micrometer publisher), driven through a success
 * and a forced failure, then asserted through the dashboard's own REST API. Confirms the whole chain
 * wires together — scanner → config API, live meters → metrics API, plus the security gate and static
 * resource handler.
 *
 * <p>Per design doc §1a rule 6 this IT lives in the {@code failover-dashboard} module; the failover
 * runtime is pulled in at <em>test</em> scope only.
 */
@SpringBootTest(properties = {
        "failover.dashboard.enabled=true",
        "failover.store.async=false",                 // synchronous writes ⇒ deterministic assertions
        "spring.security.user.name=admin",
        "spring.security.user.password=admin",
        "spring.security.user.roles=FAILOVER_ADMIN"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FailoverDashboardEndToEndIT {

    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CountryService countryService;

    @Test
    @Order(1)
    void anonymousRequestIsRejectedByTheSecurityGate() throws Exception {
        mockMvc.perform(get("/failover-dashboard/api/config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void configApiListsTheFailoverPoint() throws Exception {
        mockMvc.perform(get("/failover-dashboard/api/config").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='country-by-code')]").exists());
    }

    @Test
    @Order(3)
    void metricsMoveAfterASuccessThenAForcedFailure() throws Exception {
        // success → value stored
        countryService.failing(false);
        countryService.country("FR");

        // forced upstream failure → recover from store
        countryService.failing(true);
        countryService.country("FR");

        mockMvc.perform(get("/failover-dashboard/api/metrics").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall.totalCalls").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.overall.upstreamSuccess").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.overall.recovered").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                // derived operational signals are present (async failures, latency, top exceptions)
                .andExpect(jsonPath("$.overall.asyncFailed").exists())
                .andExpect(jsonPath("$.overall.latency.recoverMeanMs").exists())
                .andExpect(jsonPath("$.topExceptions").isArray());
    }

    @Test
    @Order(7)
    void settingsApiReturnsGroupedGlobalConfig() throws Exception {
        mockMvc.perform(get("/failover-dashboard/api/config/settings").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Core['failover.type']").exists())
                .andExpect(jsonPath("$.Store['failover.store.type']").exists())
                .andExpect(jsonPath("$.Dashboard['failover.dashboard.enabled']").value("true"));
    }

    @Test
    @Order(4)
    void healthApiClassifiesTheFailover() throws Exception {
        mockMvc.perform(get("/failover-dashboard/api/health").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='country-by-code')].status").exists());
    }

    @Test
    @Order(5)
    void failoverHealthIsUpWithTheRegisteredFailover() throws Exception {
        mockMvc.perform(get("/failover-dashboard/api/failover-health").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.details['registered-failovers']")
                        .value(org.hamcrest.Matchers.not("0")));
    }

    @Test
    @Order(6)
    void staticUiIsServedFromTheClasspath() throws Exception {
        mockMvc.perform(get("/failover-dashboard/index.html").header("Authorization", AUTH))
                .andExpect(status().isOk());
    }

    // ── test application ──────────────────────────────────────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        CountryService countryService() {
            return new CountryService();
        }
    }

    /** A referential service guarded by {@code @Failover}; {@link #failing} toggles upstream failure. */
    static class CountryService {

        private volatile boolean fail = false;

        void failing(boolean fail) {
            this.fail = fail;
        }

        @Failover(name = "country-by-code", domain = "country")
        public String country(String code) {
            if (fail) {
                throw new IllegalStateException("upstream down for " + code);
            }
            return "FR";
        }
    }
}
