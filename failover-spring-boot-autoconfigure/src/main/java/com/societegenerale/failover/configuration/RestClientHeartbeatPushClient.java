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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.observable.micrometer.HeartbeatPushClient;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link HeartbeatPushClient} backed by Spring {@link RestClient}. Lives in
 * {@code failover-spring-boot-autoconfigure} (the only module with {@code spring-web} on the classpath)
 * so the interface in {@code failover-observable-micrometer} stays free of web dependencies.
 *
 * @author Anand Manissery
 */
public class RestClientHeartbeatPushClient implements HeartbeatPushClient {

    private final RestClient client;
    private final String heartbeatUrl;

    public RestClientHeartbeatPushClient(RestClient client, String heartbeatUrl) {
        this.client = client;
        this.heartbeatUrl = heartbeatUrl;
    }

    @Override
    public void send(String instanceId) {
        client.post()
                .uri(heartbeatUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("instanceId", instanceId))
                .retrieve()
                .toBodilessEntity();
    }
}
