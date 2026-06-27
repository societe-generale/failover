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

package com.societegenerale.failover.dashboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Configuration for the embedded failover dashboard.
 *
 * <p><strong>Secure-by-default:</strong> {@code enabled} defaults to {@code false}. Nothing is
 * mapped or served until the consumer explicitly sets {@code failover.dashboard.enabled=true}
 * in YAML. See the dashboard design document, section 1a.
 *
 * <p>The {@code basePath} is the single dedicated namespace under which <em>every</em> dashboard
 * surface lives — the static UI ({@code basePath/**}) and the JSON API ({@code basePath/api/**}).
 * Override it in YAML to relocate the whole dashboard:
 * <pre>
 * failover:
 *   dashboard:
 *     enabled: true
 *     base-path: /ops/failover
 * </pre>
 * It must be a dedicated, non-root prefix so the dashboard cannot collide with the consumer's own
 * services: it must start with {@code '/'}, must not be the root {@code '/'}, and must not end with
 * a trailing {@code '/'}. A misconfigured value fails the context fast with a clear message — the
 * accepted value is then used verbatim by both the resource handler and the controller mapping, so
 * the two surfaces can never drift apart.
 *
 * <p>P0/P1 scope: master switch, {@code basePath}, config view. Exposure, security, history and
 * health-threshold properties land in later phases.
 *
 * @param enabled  master switch — when {@code false} (default) nothing is mapped or served; set
 *                 {@code true} to enable the dashboard
 * @param basePath dedicated, non-root prefix for every dashboard surface (UI + JSON API); must start
 *                 with {@code '/'}, not be {@code '/'}, and not end with {@code '/'} (default {@code /failover-dashboard})
 * @param exposure narrows what is served once enabled (UI, API, which endpoints)
 * @param security access-control posture (required role, or {@code allow-insecure} escape hatch)
 * @param history  opt-in in-memory trend history exposed at {@code /api/metrics/series}
 * @param health   health-classification thresholds on the recovery rate
 * @param cluster  where metrics are read from in a multi-instance deployment
 * @author Anand Manissery
 */
@ConfigurationProperties(prefix = "failover.dashboard")
public record DashboardProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("/failover-dashboard") String basePath,
    @DefaultValue Exposure exposure,
    @DefaultValue Security security,
    @DefaultValue History history,
    @DefaultValue Health health,
    @DefaultValue Cluster cluster
) {
    /** Canonical, binder-targeted constructor — validates the base path fail-fast. */
    @ConstructorBinding
    public DashboardProperties {
        if (basePath == null || basePath.isBlank() || !basePath.startsWith("/")
                || basePath.equals("/") || basePath.endsWith("/")) {
            throw new IllegalArgumentException(
                "failover.dashboard.base-path must be a dedicated, non-root path starting with '/' "
                    + "and without a trailing '/' (e.g. '/failover-dashboard'), but was '" + basePath + "'");
        }
    }

    /** Convenience constructor applying all defaults (used in tests/programmatic setup). */
    public DashboardProperties(boolean enabled, String basePath) {
        this(enabled, basePath, new Exposure(true, true, List.of("config", "failover-health", "metrics", "health", "cluster", "instances")),
                new Security("FAILOVER_ADMIN", false), new History(false, 120, 15), new Health(0.99, 0.90),
                new Cluster("local"));
    }

    /** Convenience constructor with custom health, default exposure/security/history/cluster. */
    public DashboardProperties(boolean enabled, String basePath, Health health) {
        this(enabled, basePath, new Exposure(true, true, List.of("config", "failover-health", "metrics", "health", "cluster", "instances")),
                new Security("FAILOVER_ADMIN", false), new History(false, 120, 15), health, new Cluster("local"));
    }

    /**
     * What the dashboard exposes once {@code enabled=true}. Everything defaults to ON — the consumer
     * decides only <em>enabled or not</em>; these flags exist solely to <em>narrow</em> exposure
     * (design doc §9 gate 3). The empty/unset state means "expose everything".
     *
     * @param ui      serve the static HTML/JS UI under {@code base-path/**} (default {@code true})
     * @param api     serve the JSON API under {@code base-path/api/**} (default {@code true})
     * @param include which API endpoints are served: any of {@code config}, {@code failover-health},
     *                {@code metrics}, {@code health} (default: all of them)
     */
    public record Exposure(
        @DefaultValue("true") boolean ui,
        @DefaultValue("true") boolean api,
        @DefaultValue({"config", "failover-health", "metrics", "health", "cluster", "instances"}) List<String> include
    ) {
        /** @return {@code true} if the named API endpoint is exposed. */
        public boolean includes(String endpoint) {
            return api && include.contains(endpoint);
        }
    }

    /**
     * Access-control posture for the dashboard (design doc §9 gate 4). When Spring Security is on the
     * classpath the module gates {@code base-path/**} behind {@code role}. When Spring Security is
     * absent the context fails fast unless {@code allowInsecure=true}, which starts with a loud WARN
     * (trusted-network / dev only). {@code allowInsecure=true} is rejected outright when the
     * {@code prod} profile is active — production must add Spring Security (I-14).
     *
     * @param role          required role for {@code base-path/**} (default {@code FAILOVER_ADMIN})
     * @param allowInsecure start without an access gate when Spring Security is absent (default {@code false});
     *                      ignored/refused under the {@code prod} profile
     */
    public record Security(
        @DefaultValue("FAILOVER_ADMIN") String role,
        @DefaultValue("false") boolean allowInsecure
    ) {
    }

    /**
     * Opt-in server-side trend history (design doc §8 option B): a fixed-size in-memory ring buffer
     * sampled on a schedule, exposed at {@code /api/metrics/series}. Process-local and lost on restart —
     * not a TSDB. Off by default.
     *
     * @param enabled               enable the ring-buffer sampler + {@code /series} endpoint (default {@code false})
     * @param samples               ring-buffer capacity, i.e. retained sample count (default {@code 120})
     * @param sampleIntervalSeconds seconds between samples (default {@code 15})
     */
    public record History(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("120") int samples,
        @DefaultValue("15") int sampleIntervalSeconds
    ) {
    }

    /**
     * Health-classification thresholds on the {@code healthyRate} (design doc §4.4):
     * {@code HEALTHY} when {@code rate >= degradedThreshold}, {@code DEGRADED} when
     * {@code rate >= unhealthyThreshold}, otherwise {@code UNHEALTHY}.
     *
     * @param degradedThreshold  healthy-rate floor for {@code HEALTHY} (default {@code 0.99})
     * @param unhealthyThreshold healthy-rate floor for {@code DEGRADED} (default {@code 0.90})
     */
    public record Health(
        @DefaultValue("0.99") double degradedThreshold,
        @DefaultValue("0.90") double unhealthyThreshold
    ) {
    }

    /**
     * Where the dashboard reads its metrics from, for correctness across a multi-instance deployment
     * (see the distributed-dashboard design document). Default {@code local} reads this instance's
     * in-process {@code MeterRegistry} only; {@code prometheus} aggregates the {@code failover.*} meters
     * across all instances via the Prometheus HTTP API; {@code shared-store} arrives in a later phase.
     *
     * @param mode        {@code local} (default) | {@code prometheus} | {@code shared-store}
     * @param prometheus  Prometheus connection settings, used when {@code mode=prometheus}
     * @param sharedStore in-memory snapshot-aggregation settings, used when {@code mode=shared-store}
     * @param snapshot    peer-side push settings, used when {@code mode=shared-store}
     */
    public record Cluster(
        @DefaultValue("local") String mode,
        @DefaultValue Prometheus prometheus,
        @DefaultValue SharedStore sharedStore,
        @DefaultValue Snapshot snapshot
    ) {
        /** Canonical, binder-targeted constructor (disambiguates from the convenience one below). */
        @ConstructorBinding
        public Cluster {
        }

        /** Convenience: a cluster mode with default sub-settings (used in tests / programmatic setup). */
        public Cluster(String mode) {
            this(mode, new Prometheus("", "", 5), new SharedStore(), new Snapshot());
        }
    }

    /**
     * Settings for {@code cluster.mode=shared-store} — the self-contained small-cluster tier (≤ ~10 instances)
     * where peers push their KPI snapshot to the dashboard and it aggregates them in memory, with no Prometheus.
     * Production-supported for small deployments; data quality/consistency is prioritised over durability.
     *
     * @param livenessSeconds a snapshot older than this is excluded from the aggregate (peer treated as silent)
     *                        and surfaced in {@code SourceInfo} (default {@code 45})
     * @param maxInstances    supported small-cluster ceiling; beyond it a warning is logged (default {@code 10})
     */
    public record SharedStore(
        @DefaultValue("inmemory") String store,
        @DefaultValue("45") int livenessSeconds,
        @DefaultValue("10") int maxInstances,
        @DefaultValue Retention retention,
        @DefaultValue("30") int sampleIntervalSeconds,
        @DefaultValue Jdbc jdbc
    ) {
        /** Convenience with defaults. */
        public SharedStore() {
            this("inmemory", 45, 10, new Retention(), 30, new Jdbc());
        }
    }

    /**
     * JDBC durability settings for {@code cluster.shared-store.store=jdbc} (the optional
     * {@code failover-dashboard-snapshotstore-jdbc} module). The snapshot table is keyed by instance id; per the
     * config-namespace convention, store-specific keys live under {@code shared-store.jdbc.*}.
     *
     * <p>Mirrors the {@code failover.store.jdbc.table-prefix} strategy: the table name is {@code <prefix> +
     * FAILOVER_DASHBOARD_SNAPSHOT}. The prefix is validated (letters/digits/underscore only) since it is
     * concatenated into SQL — use it to namespace per environment or per tenant (one table per tenant).
     *
     * @param tablePrefix prefix prepended to the base table {@code FAILOVER_DASHBOARD_SNAPSHOT} (default {@code ""});
     *                    letters/digits/underscore only
     * @param autoDdl     create the table on startup if missing (default {@code true}); disable to manage the schema yourself
     */
    public record Jdbc(
        @DefaultValue("") String tablePrefix,
        @DefaultValue("true") boolean autoDdl
    ) {
        /** Convenience with defaults. */
        public Jdbc() {
            this("", true);
        }
    }

    /**
     * Bounded retention for the cluster trend history (design §5.4): the series ring keeps points no older than
     * {@code maxAge} and no more than {@code maxEntries}, truncating the oldest first. Caps heap; this is bounded
     * trend history, not a TSDB.
     *
     * @param maxAge     drop series points older than this (e.g. {@code 7d}; configurable 5–10 days)
     * @param maxEntries hard cap on retained points; oldest truncated first (default {@code 100000})
     */
    public record Retention(
        @DefaultValue("7d") java.time.Duration maxAge,
        @DefaultValue("100000") int maxEntries
    ) {
        /** Convenience with defaults. */
        public Retention() {
            this(java.time.Duration.ofDays(7), 100_000);
        }
    }

    /**
     * Peer-side push settings for {@code cluster.mode=shared-store}: each instance periodically POSTs its local
     * {@code MetricsSummary} snapshot to the dashboard's ingest endpoint. Inactive when {@code publishUrl} is blank.
     *
     * <p>Auth priority (publisher side — what credentials the peer sends):
     * <ol>
     *   <li>{@code oauth2-client-registration-id} set and {@code OAuth2AuthorizedClientManager} in context
     *       → Bearer token via the consumer's existing OAuth2 client (no new dependencies).</li>
     *   <li>{@code username} + {@code password} set → HTTP Basic Auth (no Spring Security required on the peer).</li>
     *   <li>Neither → POST without credentials (insecure; acceptable on trusted internal networks).</li>
     * </ol>
     *
     * <p>The dashboard (receiver) side mirrors this: it creates a matching security filter chain for
     * {@code base-path/api/cluster/snapshot} — OAuth2 JWT validation, HTTP Basic, or open (permit-all).
     *
     * @param publishUrl                  dashboard ingest URL, e.g. {@code http://dashboard:8080/failover-dashboard/api/cluster/snapshot}
     *                                    (blank ⇒ this instance does not push)
     * @param intervalSeconds             seconds between snapshot pushes (default {@code 15})
     * @param username                    HTTP Basic Auth username for the ingest endpoint (blank ⇒ no Basic Auth)
     * @param password                    HTTP Basic Auth password — <strong>must be plain text</strong>; the publisher
     *                                    sends it as-is in the {@code Authorization: Basic} header and the dashboard
     *                                    stores it with {@code {noop}} encoding internally. Spring Security encoded
     *                                    strings (e.g. {@code {bcrypt}…}) must not be used here — they would be sent
     *                                    literally and never match.
     * @param oauth2ClientRegistrationId  id of an existing {@code spring.security.oauth2.client.registration.*} to use for Bearer auth
     *                                    (blank ⇒ no OAuth2; takes precedence over Basic Auth when set)
     */
    public record Snapshot(
        @DefaultValue("") String publishUrl,
        @DefaultValue("15") int intervalSeconds,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("") String oauth2ClientRegistrationId,
        @DefaultValue("false") boolean allowInsecureIngest
    ) {
        @ConstructorBinding
        public Snapshot {
        }

        /** Convenience with defaults. */
        public Snapshot() {
            this("", 15, "", "", "", false);
        }
    }

    /**
     * Prometheus HTTP-API connection settings for {@code cluster.mode=prometheus}. The dashboard issues
     * read-only {@code /api/v1/query} requests to aggregate the {@code failover.*} meters across instances.
     * If {@code base-url} is blank, or Prometheus is unreachable at runtime, the source falls back to this
     * instance's local registry (with a warning) so the dashboard never goes dark.
     *
     * @param baseUrl        Prometheus base URL, e.g. {@code http://prometheus:9090} (blank ⇒ disabled, falls back to local)
     * @param token          optional bearer token sent as {@code Authorization: Bearer <token>} (blank ⇒ none)
     * @param timeoutSeconds connect/read timeout for each query (default {@code 5})
     */
    public record Prometheus(
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String token,
        @DefaultValue("5") int timeoutSeconds
    ) {
    }
}
