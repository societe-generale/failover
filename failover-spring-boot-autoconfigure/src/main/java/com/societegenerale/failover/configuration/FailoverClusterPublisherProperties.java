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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Peer-side snapshot-publisher configuration. Properties mirror the dashboard's
 * {@code failover.dashboard.cluster.snapshot.*} namespace so the YAML is unchanged — the same
 * configuration that previously activated the publisher via the dashboard artifact now activates it
 * via the failover starter on any peer app.
 *
 * <p>Auth priority (publisher side):
 * <ol>
 *   <li>{@code oauth2-client-registration-id} → Bearer token via {@code OAuth2AuthorizedClientManager}</li>
 *   <li>{@code username} + {@code password} → HTTP Basic Auth</li>
 *   <li>Neither → open POST (insecure; warn unless {@code allow-insecure-ingest=true})</li>
 * </ol>
 *
 * @param publishUrl                 dashboard ingest URL; blank means this instance does not push
 * @param intervalSeconds            throttle interval for snapshot pushes (default {@code 15})
 * @param retryIntervalSeconds       seconds to wait before retrying after a push failure (default {@code 300})
 * @param username                   HTTP Basic username for the ingest endpoint (blank ⇒ no Basic Auth)
 * @param password                   HTTP Basic password
 * @param oauth2ClientRegistrationId OAuth2 client registration id for Bearer auth (takes priority over Basic)
 * @param allowInsecureIngest        suppress the no-auth warning (trusted-network / dev only)
 * @param heartbeat                  optional lightweight liveness heartbeat settings (default disabled)
 * @author Anand Manissery
 */
@ConfigurationProperties(prefix = "failover.dashboard.cluster.snapshot")
public record FailoverClusterPublisherProperties(
        @DefaultValue("") String publishUrl,
        @DefaultValue("15") int intervalSeconds,
        @DefaultValue("300") int retryIntervalSeconds,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("") String oauth2ClientRegistrationId,
        @DefaultValue("false") boolean allowInsecureIngest,
        @DefaultValue Heartbeat heartbeat
) {
    @ConstructorBinding
    public FailoverClusterPublisherProperties {
    }

    /** Convenience with all defaults (used in tests / programmatic setup). */
    public FailoverClusterPublisherProperties() {
        this("", 15, 300, "", "", "", false, new Heartbeat());
    }

    /**
     * Lightweight heartbeat ping settings. When enabled, this instance sends a minimal ping (instance id
     * only, no metrics payload) to the dashboard at a fixed interval so the dashboard can detect crashes
     * independently of metric events.
     *
     * <p>The heartbeat URL defaults to the snapshot URL with {@code /snapshot} replaced by {@code /heartbeat}.
     * Override {@code url} explicitly for non-standard dashboard paths.
     *
     * @param enabled         send periodic heartbeat pings (default {@code false})
     * @param intervalSeconds seconds between pings (default {@code 60})
     * @param url             explicit heartbeat endpoint URL; blank ⇒ derived from {@code publish-url}
     */
    public record Heartbeat(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("60") int intervalSeconds,
            @DefaultValue("") String url
    ) {
        @ConstructorBinding
        public Heartbeat {
        }

        public Heartbeat() {
            this(false, 60, "");
        }
    }
}
