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

import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.micrometer.SnapshotPushClient;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * {@link SnapshotPushClient} backed by Spring's {@link RestClient}. Registered by
 * {@link FailoverMicrometerAutoConfiguration} when {@code failover.dashboard.cluster.snapshot.publish-url}
 * is set. Throws on failure so {@link com.societegenerale.failover.observable.micrometer.ClusterSnapshotPublisher}
 * can apply its backoff and log-once policy.
 *
 * @author Anand Manissery
 */
public class RestClientSnapshotPushClient implements SnapshotPushClient {

    private final RestClient client;
    private final String publishUrl;

    public RestClientSnapshotPushClient(RestClient client, String publishUrl) {
        this.client = client;
        this.publishUrl = publishUrl;
    }

    @Override
    public void send(ClusterSnapshot snapshot) {
        client.post()
                .uri(publishUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(snapshot)
                .retrieve()
                .toBodilessEntity();
    }
}
