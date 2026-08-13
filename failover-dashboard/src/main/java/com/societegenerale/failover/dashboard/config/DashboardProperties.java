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

import java.time.Duration;
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
        if (basePath == null || basePath.isBlank() || !basePath.startsWith("/") || basePath.endsWith("/")) {
            throw new IllegalArgumentException(
                "failover.dashboard.base-path must be a dedicated, non-root path starting with '/' "
                    + "and without a trailing '/' (e.g. '/failover-dashboard'), but was '" + basePath + "'");
        }
    }

    /**
     * Convenience constructor applying all defaults (used in tests/programmatic setup).
     *
     * @param enabled  master switch
     * @param basePath dedicated, non-root base path
     */
    public DashboardProperties(boolean enabled, String basePath) {
        this(enabled, basePath, new Exposure(true, true, List.of("config", "failover-health", "metrics", "health", "cluster", "instances")),
                new Security(SecurityType.AUTHORITY, "FAILOVER_ADMIN", "FAILOVER_ADMIN", null, false, "", false), new History(false, 120, 15), new Health(0.99, 0.90, 100),
                new Cluster("local"));
    }

    /**
     * Convenience constructor with custom health, default exposure/security/history/cluster.
     *
     * @param enabled  master switch
     * @param basePath dedicated, non-root base path
     * @param health   health-classification thresholds
     */
    public DashboardProperties(boolean enabled, String basePath, Health health) {
        this(enabled, basePath, new Exposure(true, true, List.of("config", "failover-health", "metrics", "health", "cluster", "instances")),
                new Security(SecurityType.AUTHORITY, "FAILOVER_ADMIN", "FAILOVER_ADMIN", null, false, "", false), new History(false, 120, 15), health, new Cluster("local"));
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
        /**
         * Checks whether the named API endpoint is exposed.
         *
         * @param endpoint the endpoint name (e.g. {@code "config"}, {@code "metrics"})
         * @return {@code true} if the named API endpoint is exposed.
         */
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
     * @param type          {@code AUTHORITY} (default), {@code ROLE}, or {@code EXPRESSION} — which
     *                      Spring Security check to use
     * @param role          required role for {@code base-path/**} when {@code type=ROLE} (default {@code FAILOVER_ADMIN})
     * @param authority     required authority for {@code base-path/**} when {@code type=AUTHORITY} (default {@code FAILOVER_ADMIN})
     * @param expression    SpEL web-security expression evaluated for {@code base-path/**} when
     *                      {@code type=EXPRESSION}, e.g. {@code "hasAnyRole('ADMIN') or hasAnyAuthority('WRITE_PRIVILEGE')"};
     *                      required (non-blank) when {@code type=EXPRESSION}
     * @param allowInsecure start without an access gate when Spring Security is absent (default {@code false});
     *                      ignored/refused under the {@code prod} profile
     * @param oauth2ClientRegistrationId Spring Security client-registration id to secure the dashboard UI
     *                      with OAuth2 login instead of HTTP Basic (blank ⇒ disabled, the default); requires
     *                      {@code spring-security-oauth2-client} on the classpath. Authorization still goes
     *                      through {@code type}/{@code role}/{@code authority}/{@code expression} — only the
     *                      authentication mechanism changes. See the {@code GrantedAuthoritiesMapper} note in
     *                      the dashboard security docs for mapping IdP claims onto {@code FAILOVER_ADMIN}.
     * @param oauth2ResourceServer secure the dashboard UI/API with OAuth2 resource-server (JWT Bearer)
     *                      validation instead of a browser login (default {@code false}); for consumers whose
     *                      SSO is terminated upstream (gateway/sidecar) and forwards a validated JWT on every
     *                      request. Requires {@code spring-security-oauth2-resource-server} on the classpath
     *                      and the standard {@code spring.security.oauth2.resourceserver.jwt.*} properties.
     *                      Ignored when {@code oauth2ClientRegistrationId} is set or a
     *                      {@code DashboardAuthenticationConfigurer} bean is present.
     */
    public record Security(
        @DefaultValue("AUTHORITY") SecurityType type,
        @DefaultValue("FAILOVER_ADMIN") String role,
        @DefaultValue("FAILOVER_ADMIN") String authority,
        String expression,
        @DefaultValue("false") boolean allowInsecure,
        @DefaultValue("") String oauth2ClientRegistrationId,
        @DefaultValue("false") boolean oauth2ResourceServer
    ) {
        /** Canonical, binder-targeted constructor — validates the expression is set when required. */
        @ConstructorBinding
        public Security {
            if (type == SecurityType.EXPRESSION && (expression == null || expression.isBlank())) {
                throw new IllegalArgumentException(
                    "failover.dashboard.security.expression must be set (non-blank) when "
                        + "failover.dashboard.security.type=EXPRESSION");
            }
        }
    }

    /**
     * Security type for failover — AUTHORITY, ROLE, or EXPRESSION. This is used to determine how the
     * role/authority/expression is interpreted in the security configuration.
     * If AUTHORITY (by default), it will perform the check on configured authority (hasAuthority(authority));
     * if ROLE, it will perform the check on configured role (hasRole(role)); if EXPRESSION, it
     * will evaluate the configured SpEL web-security expression (e.g. {@code "hasAnyRole('ADMIN') or hasAnyAuthority('WRITE_PRIVILEGE')"}).
     */
    public enum SecurityType {
        /** Gate checked via {@code hasRole(role)}. */
        ROLE,
        /** Gate checked via {@code hasAuthority(authority)}. */
        AUTHORITY,
        /** Gate checked via evaluating the configured SpEL {@code expression}. */
        EXPRESSION
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
     * <p>The rate itself is computed over only the most recent {@code sampleSize} calls per failover
     * point, not the lifetime total: a cumulative rate never fully recovers from an old bad spell (a
     * handful of errors from hours ago keep dragging a now-healthy endpoint into DEGRADED), so
     * classification instead uses a bounded trailing window that ages old outcomes out.
     *
     * @param degradedThreshold  healthy-rate floor for {@code HEALTHY} (default {@code 0.99})
     * @param unhealthyThreshold healthy-rate floor for {@code DEGRADED} (default {@code 0.90})
     * @param sampleSize         number of most-recent calls, per failover point, considered when
     *                           computing the rate for classification (default {@code 100}); must be {@code > 0}
     */
    public record Health(
        @DefaultValue("0.99") double degradedThreshold,
        @DefaultValue("0.90") double unhealthyThreshold,
        @DefaultValue("100") int sampleSize
    ) {
        /** Canonical, binder-targeted constructor — validates the sample size fail-fast. */
        public Health {
            if (sampleSize <= 0) {
                throw new IllegalArgumentException(
                    "failover.dashboard.health.sample-size must be > 0, but was " + sampleSize);
            }
        }
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

        /**
         * Convenience: a cluster mode with default sub-settings (used in tests / programmatic setup).
         *
         * @param mode {@code local} | {@code prometheus} | {@code shared-store}
         */
        public Cluster(String mode) {
            this(mode, new Prometheus("", "", 5), new SharedStore(), new Snapshot());
        }
    }

    /**
     * Settings for {@code cluster.mode=shared-store} — the self-contained small-cluster tier (≤ ~10 instances)
     * where peers push their KPI snapshot to the dashboard and it aggregates them in memory, with no Prometheus.
     * Production-supported for small deployments; data quality/consistency is prioritised over durability.
     *
     * <p>Counts are never dropped from the cluster aggregate: each instance always contributes its last-known
     * values, and a peer restart (counter reset) folds the pre-restart totals into a carried-forward baseline.
     * Instances not seen within {@code instanceRetention} are retired from the Instances tab (keeping the
     * in-memory store bounded under pod churn) while their counts keep contributing to the aggregate.
     * Per-instance staleness is visible through each row's {@code lastSeenEpochMs} timestamp.
     *
     * @param livenessSeconds   heartbeat age threshold in seconds — an instance is {@code DOWN} when no heartbeat
     *                          was received within this window; {@code UNKNOWN} if no heartbeat ever received
     *                          (default {@code 180}; rule of thumb: ≥ 3 × peer {@code heartbeat.interval-seconds})
     * @param maxInstances      supported small-cluster ceiling; beyond it a warning is logged (default {@code 10})
     * @param instanceRetention retire instances not seen for this long from the per-instance view — their counts
     *                          stay in the aggregate (default {@code 7d}; {@code 0} keeps every instance forever;
     *                          applies to the in-memory store only — the durable JDBC store retains all rows)
     * @param store             backing store for pushed snapshots: {@code inmemory} (default) or {@code jdbc}
     * @param retention         bounded retention for the cluster trend history
     * @param sampleIntervalSeconds seconds between cluster-trend samples (default {@code 30})
     * @param jdbc              JDBC durability settings, used when {@code store=jdbc}
     * @param liveness          dashboard-side toggle for heartbeat liveness tracking (ADR 66); off by default
     */
    public record SharedStore(
        @DefaultValue("inmemory") String store,
        @DefaultValue("180") int livenessSeconds,
        @DefaultValue("10") int maxInstances,
        @DefaultValue("7d") Duration instanceRetention,
        @DefaultValue Retention retention,
        @DefaultValue("30") int sampleIntervalSeconds,
        @DefaultValue Jdbc jdbc,
        @DefaultValue Liveness liveness
    ) {
        /** Canonical, binder-targeted constructor (disambiguates from the convenience one below). */
        @ConstructorBinding
        public SharedStore {
        }

        /** Convenience with defaults (used in tests / programmatic setup). */
        public SharedStore() {
            this("inmemory", 180, 10, Duration.ofDays(7), new Retention(), 30, new Jdbc(), new Liveness());
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
     * <p>The snapshot table is never created or altered by the failover module — schema management (including
     * upgrades when new columns are added) is the consuming service's responsibility. See the module docs for the
     * DDL to run per dialect.
     *
     * @param tablePrefix prefix prepended to the base table {@code FAILOVER_DASHBOARD_SNAPSHOT} (default {@code ""});
     *                    letters/digits/underscore only
     */
    public record Jdbc(
        @DefaultValue("") String tablePrefix
    ) {
        /** Canonical, binder-targeted constructor (disambiguates from the convenience one below). */
        @ConstructorBinding
        public Jdbc {
        }

        /** Convenience with defaults. */
        public Jdbc() {
            this("");
        }
    }

    /**
     * Dashboard-side toggle for heartbeat liveness tracking (ADR 66), separate from the peer-side
     * {@code cluster.snapshot.heartbeat.enabled} push flag. Off by default: when disabled, no
     * {@code HeartbeatStore} bean is created at all (neither the in-memory default nor, under
     * {@code store=jdbc}, the durable JDBC one) — the ingest endpoint ({@code /api/cluster/heartbeat}) is
     * not mapped, {@code SharedStoreMetricsSource} never queries the store (every instance stays
     * {@code LiveStatus.UNKNOWN}), and — critically for {@code store=jdbc} — the
     * {@code FAILOVER_DASHBOARD_HEARTBEAT} table is never required to exist. Enable only when at least one
     * peer also sets {@code cluster.snapshot.heartbeat.enabled=true}; enabling one side without the other
     * leaves every instance at {@code UNKNOWN} (dashboard side) or wastes pushes nobody reads (peer side).
     *
     * @param enabled turn on dashboard-side heartbeat liveness tracking (default {@code false})
     */
    public record Liveness(
        @DefaultValue("false") boolean enabled
    ) {
        /** Canonical, binder-targeted constructor (disambiguates from the convenience one below). */
        @ConstructorBinding
        public Liveness {
        }

        /** Convenience with defaults. */
        public Liveness() {
            this(false);
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
        @DefaultValue("7d") Duration maxAge,
        @DefaultValue("100000") int maxEntries
    ) {
        /** Convenience with defaults. */
        public Retention() {
            this(Duration.ofDays(7), 100_000);
        }
    }

    /**
     * Snapshot push / ingest settings for {@code cluster.mode=shared-store}.
     *
     * <p><strong>Peer (publisher) side</strong> — each peer instance POSTs its local {@code MetricsSummary} to the
     * dashboard's ingest endpoint. Set {@code publishUrl} to the dashboard base URL including context path
     * (e.g. {@code http://dashboard:8080/failover-dashboard}); {@code /api/cluster/snapshot} is appended automatically.
     * Inactive when {@code publishUrl} is blank.
     *
     * <p><strong>Dashboard (receiver) side</strong> — controls which security gate protects the ingest endpoint:
     * <ul>
     *   <li>{@code username} + {@code password} → HTTP Basic, dedicated in-memory user</li>
     *   <li>{@code oauth2-client-registration-id} → OAuth2 JWT Bearer (requires
     *       {@code spring-security-oauth2-resource-server} on the classpath)</li>
     *   <li>{@code allow-insecure-ingest=true} → permit-all (trusted internal network only)</li>
     * </ul>
     *
     * @param publishUrl                 dashboard base URL including context path, e.g. {@code http://dashboard:8080/failover-dashboard} (blank ⇒ this instance does not push); {@code /api/cluster/snapshot} is appended automatically
     * @param intervalSeconds            seconds between pushes (default {@code 15})
     * @param username                   ingest Basic-auth username accepted by the dashboard (blank ⇒ Basic disabled)
     * @param password                   ingest Basic-auth password; may be pre-encoded ({@code {bcrypt}…})
     * @param oauth2ClientRegistrationId Spring Security resource-server registration id for JWT validation (blank ⇒ disabled)
     * @param allowInsecureIngest        {@code true} to allow ingest without any auth gate (not recommended in production)
     * @param ingest                     controls whether the HTTP ingest endpoint ({@code ClusterSnapshotController})
     *                                    is mapped at all — turn off when every peer writes via JDBC-direct instead
     */
    public record Snapshot(
        @DefaultValue("") String publishUrl,
        @DefaultValue("15") int intervalSeconds,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("") String oauth2ClientRegistrationId,
        @DefaultValue("false") boolean allowInsecureIngest,
        @DefaultValue Ingest ingest
    ) {
        /** Canonical constructor used by Spring Boot's relaxed property binder. */
        @ConstructorBinding
        public Snapshot {
        }

        /** Convenience with all defaults (used in tests / programmatic setup). */
        public Snapshot(String publishUrl, int intervalSeconds, String username, String password,
                         String oauth2ClientRegistrationId, boolean allowInsecureIngest) {
            this(publishUrl, intervalSeconds, username, password, oauth2ClientRegistrationId, allowInsecureIngest,
                    new Ingest());
        }

        /** Convenience with defaults. */
        public Snapshot() {
            this("", 15, "", "", "", false);
        }
    }

    /**
     * Toggle for the HTTP snapshot-ingest endpoint ({@code ClusterSnapshotController}, mapped at
     * {@code base-path/api/cluster/snapshot}). On by default — turning it off does not disable
     * {@code cluster.mode=shared-store} itself, only the HTTP path into it; the dashboard still reads
     * from whichever {@code SnapshotStore} is configured (in-memory or JDBC). Set {@code false} once every
     * peer writes directly to the shared JDBC table ({@code failover.dashboard.cluster.snapshot.jdbc.enabled=true}
     * on the peer side) so the endpoint — and its ingest security config — is never mapped at all.
     *
     * @param enabled map the ingest endpoint (default {@code true})
     */
    public record Ingest(
        @DefaultValue("true") boolean enabled
    ) {
        /** Canonical, binder-targeted constructor (disambiguates from the convenience one below). */
        @ConstructorBinding
        public Ingest {
        }

        /** Convenience with defaults. */
        public Ingest() {
            this(true);
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
