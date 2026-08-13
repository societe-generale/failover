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

import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({ClusterSnapshotController.class, ClusterSnapshotControllerTest.TestApp.class})
@DisplayName("ClusterSnapshotController — POST /failover-dashboard/api/cluster/snapshot")
class ClusterSnapshotControllerTest {

    /** Minimal well-formed summary; the controller only checks for its presence. */
    private static final String SUMMARY = """
            {"overall":null,"perApi":[],"topExceptions":[],"timestamp":0}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SnapshotStore snapshotStore;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        // to boot the test beans
    }

    private String body(String instanceIdJson) {
        return "{\"instanceId\":" + instanceIdJson + ",\"summary\":" + SUMMARY + ",\"configEntries\":[]}";
    }

    @Test
    @DisplayName("POST /snapshot records a well-formed snapshot and returns 202")
    void recordsSnapshot() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"orders-svc:host-1:8080\"")))
                .andExpect(status().isAccepted());

        verify(snapshotStore).upsert(any());
    }

    @Test
    @DisplayName("POST /snapshot with no instanceId returns 400 and stores nothing")
    void rejectsMissingInstanceId() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":" + SUMMARY + "}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(snapshotStore);
    }

    @Test
    @DisplayName("POST /snapshot with a markup-bearing instanceId returns 400 — never reaches the store or the UI")
    void rejectsMarkupInInstanceId() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"<img src=x onerror=alert(1)>\"")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(snapshotStore);
    }

    @Test
    @DisplayName("POST /snapshot with an over-long instanceId returns 400 — bounds the stored key")
    void rejectsOverlongInstanceId() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"" + "a".repeat(201) + "\"")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(snapshotStore);
    }

    @Test
    @DisplayName("POST /snapshot with no summary returns 400 — a null summary would 500 every later read")
    void rejectsMissingSummary() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"app-1\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(snapshotStore);
    }

    @Test
    @DisplayName("POST /snapshot with no configEntries is accepted — the record normalises null to empty")
    void acceptsMissingConfigEntries() throws Exception {
        mockMvc.perform(post("/failover-dashboard/api/cluster/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"app-1\",\"summary\":" + SUMMARY + "}"))
                .andExpect(status().isAccepted());

        verify(snapshotStore).upsert(any());
    }
}
