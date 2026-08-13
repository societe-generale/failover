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

package com.societegenerale.failover.dashboard.web;

import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({ClusterHeartbeatController.class, ClusterHeartbeatControllerTest.TestApp.class})
@DisplayName("ClusterHeartbeatController — POST /failover-dashboard/api/cluster/heartbeat")
class ClusterHeartbeatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HeartbeatStore heartbeatStore;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        // to boot the test beans
    }

    @Test
    @DisplayName("POST /heartbeat records instanceId and returns 202")
    void heartbeatRecordsInstance() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"app-host:8080\"}"))
                .andExpect(status().isAccepted());

        verify(heartbeatStore).record("app-host:8080");
    }

    @Test
    @DisplayName("POST /heartbeat with no instanceId returns 400 and records nothing")
    void rejectsMissingInstanceId() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(heartbeatStore);
    }

    @Test
    @DisplayName("POST /heartbeat with a blank instanceId returns 400 and records nothing")
    void rejectsBlankInstanceId() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(heartbeatStore);
    }

    @Test
    @DisplayName("POST /heartbeat with a markup-bearing instanceId returns 400 — never reaches the store or the UI")
    void rejectsMarkupInInstanceId() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"<img src=x onerror=alert(1)>\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(heartbeatStore);
    }

    @Test
    @DisplayName("POST /heartbeat with an over-long instanceId returns 400 — bounds the stored key")
    void rejectsOverlongInstanceId() throws Exception {
        String tooLong = "a".repeat(201);
        mockMvc.perform(post("/failover-dashboard/api/cluster/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(heartbeatStore);
    }

    @Test
    @DisplayName("POST /heartbeat accepts the separators real instance ids use")
    void acceptsRealisticInstanceIds() throws Exception {
        for (String id : new String[]{"orders-svc:host-1:8080", "pod_abc.ns.svc", "a/b@c", "9f1c2e3d-4b5a"}) {
            mockMvc.perform(post("/failover-dashboard/api/cluster/heartbeat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"instanceId\":\"" + id + "\"}"))
                    .andExpect(status().isAccepted());
            verify(heartbeatStore).record(id);
        }
    }
}
