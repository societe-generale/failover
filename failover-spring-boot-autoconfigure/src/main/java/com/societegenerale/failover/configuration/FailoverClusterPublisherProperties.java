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
 * @param publishUrl                 dashboard base URL including context path (e.g. {@code http://dashboard:8080/failover-dashboard}); {@code /api/cluster/snapshot} is appended automatically. Blank means this instance does not push.
 * @param intervalSeconds            throttle interval for snapshot pushes (default {@code 15})
 * @param retryIntervalSeconds       seconds to wait before retrying after a push failure (default {@code 300})
 * @param username                   HTTP Basic username for the ingest endpoint (blank ⇒ no Basic Auth)
 * @param password                   HTTP Basic password
 * @param oauth2ClientRegistrationId OAuth2 client registration id for Bearer auth (takes priority over Basic)
 * @param allowInsecureIngest        suppress the no-auth warning (trusted-network / dev only)
 * @param heartbeat                  optional lightweight liveness heartbeat settings (default disabled)
 * @param jdbc                       JDBC-direct settings — write straight to the dashboard's shared-store table
 *                                    instead of pushing over HTTP (default disabled)
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
        @DefaultValue Heartbeat heartbeat,
        @DefaultValue Jdbc jdbc
) {
    /** Canonical constructor used by Spring Boot's relaxed property binder. */
    @ConstructorBinding
    public FailoverClusterPublisherProperties {
        if (jdbc.enabled() && publishUrl != null && !publishUrl.isBlank()) {
            throw new IllegalArgumentException(
                "failover.dashboard.cluster.snapshot.jdbc.enabled=true and publish-url are mutually exclusive — "
                    + "set only one transport for the shared-store peer publisher.");
        }
    }

    /** Convenience with all defaults (used in tests / programmatic setup). */
    public FailoverClusterPublisherProperties() {
        this("", 15, 300, "", "", "", false, new Heartbeat(), new Jdbc());
    }

    /**
     * Lightweight heartbeat ping settings. When enabled, this instance sends a minimal ping (instance id
     * only, no metrics payload) to the dashboard at a fixed interval so the dashboard can detect crashes
     * independently of metric events.
     *
     * <p>The heartbeat URL is always derived from {@code publish-url}: {@code <publish-url>/api/cluster/heartbeat}.
     *
     * @param enabled         send periodic heartbeat pings (default {@code false})
     * @param intervalSeconds seconds between pings (default {@code 60})
     */
    public record Heartbeat(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("60") int intervalSeconds
    ) {
        /** Canonical constructor used by Spring Boot's relaxed property binder. */
        @ConstructorBinding
        public Heartbeat {
        }

        /** Convenience with all defaults (used in tests / programmatic setup). */
        public Heartbeat() {
            this(false, 60);
        }
    }

    /**
     * JDBC-direct settings for the shared-store peer publisher: when {@code enabled}, this instance writes its
     * {@link com.societegenerale.failover.observable.metrics.ClusterSnapshot} straight into the dashboard's
     * {@code FAILOVER_DASHBOARD_SNAPSHOT} table (same schema, same reset-aware baseline carry-forward as the
     * dashboard's own {@code SnapshotStoreJdbc}) instead of POSTing to the ingest endpoint. Requires this
     * instance to have its own {@link javax.sql.DataSource} pointed at the dashboard's database, and
     * {@code table-prefix} must match the dashboard's {@code cluster.shared-store.jdbc.table-prefix} — both
     * sides read/write the same table. Mutually exclusive with {@code publish-url}: set only one transport.
     *
     * <p>Trades the ingest endpoint (and its HTTP auth gate) for a DB credential/network dependency — only
     * worth it when this instance and the dashboard already share the database (e.g. both point
     * {@code failover.store.type=jdbc} / {@code cluster.shared-store.store=jdbc} at the same schema).
     *
     * @param enabled     write snapshots directly to the shared-store JDBC table instead of pushing over HTTP
     *                    (default {@code false})
     * @param tablePrefix prefix prepended to the base table {@code FAILOVER_DASHBOARD_SNAPSHOT} (default {@code ""});
     *                    letters/digits/underscore only; must match the dashboard's configured prefix
     */
    public record Jdbc(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String tablePrefix
    ) {
        /** Canonical constructor used by Spring Boot's relaxed property binder. */
        @ConstructorBinding
        public Jdbc {
        }

        /** Convenience with all defaults (used in tests / programmatic setup). */
        public Jdbc() {
            this(false, "");
        }
    }
}
