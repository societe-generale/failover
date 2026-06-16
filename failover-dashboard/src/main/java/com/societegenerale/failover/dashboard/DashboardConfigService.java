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
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.dashboard.dto.ConfigEntry;
import com.societegenerale.failover.dashboard.dto.FailoverHealth;
import org.springframework.core.env.Environment;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the configuration view: one {@link ConfigEntry} per {@code @Failover} point discovered by
 * the {@link FailoverScanner}, enriched with the global framework settings.
 *
 * <p>Global settings are read from the {@link Environment} (the {@code failover.*} keys) rather than
 * the typed {@code FailoverProperties} bean, keeping this module decoupled from
 * {@code failover-spring-boot-autoconfigure} (design doc §11). Reads only configuration metadata —
 * never connection details or payload data (§9).
 *
 * @author Anand Manissery
 */
public class DashboardConfigService {

    private static final String DEFAULT = "default";

    private final FailoverScanner scanner;
    private final Environment environment;

    public DashboardConfigService(FailoverScanner scanner, Environment environment) {
        this.scanner = scanner;
        this.environment = environment;
    }

    /**
     * @return one {@link ConfigEntry} per discovered {@code @Failover}, sorted by name; never {@code null}.
     */
    public List<ConfigEntry> configEntries() {
        String storeType = environment.getProperty("failover.store.type", "inmemory");
        String executionType = environment.getProperty("failover.type", "basic");
        String exceptionPolicy = environment.getProperty("failover.exception-policy", "rethrow");
        boolean asyncStore = environment.getProperty("failover.store.async", Boolean.class, Boolean.TRUE);

        return scanner.findAllFailover().stream()
                .map(f -> toEntry(f, storeType, executionType, exceptionPolicy, asyncStore))
                .sorted(Comparator.comparing(ConfigEntry::name))
                .toList();
    }

    /**
     * Actuator-style overall failover health: {@code UP} when at least one {@code @Failover} is
     * registered, {@code DOWN} when none are discovered. Details echo the global config (types/flags
     * only) read from the {@link Environment}, mirroring the {@code /actuator/health/failover} contributor.
     *
     * @return the failover health snapshot
     */
    public FailoverHealth failoverHealth() {
        int registered = scanner.findAllFailover().size();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("registered-failovers", Integer.toString(registered));
        details.put("enabled", environment.getProperty("failover.enabled", "true"));
        details.put("type", environment.getProperty("failover.type", "BASIC"));
        details.put("exception-policy", environment.getProperty("failover.exception-policy", "RETHROW"));
        details.put("store.type", environment.getProperty("failover.store.type", "INMEMORY"));
        details.put("store.async", environment.getProperty("failover.store.async", "true"));
        details.put("store.jdbc.table-prefix", environment.getProperty("failover.store.jdbc.table-prefix", ""));
        details.put("scheduler.enabled", environment.getProperty("failover.scheduler.enabled", "true"));
        return new FailoverHealth(registered == 0 ? "DOWN" : "UP", details);
    }

    private ConfigEntry toEntry(Failover f, String storeType, String executionType,
                                String exceptionPolicy, boolean asyncStore) {
        return new ConfigEntry(
                f.name(),
                orDefault(f.domain()),
                f.expiryDuration(),
                f.expiryUnit().name(),
                f.recoverAll(),
                orDefault(f.payloadSplitter()),
                orDefault(f.keyGenerator()),
                orDefault(f.expiryPolicy()),
                storeType,
                executionType,
                exceptionPolicy,
                asyncStore);
    }

    /** Empty per-annotation overrides render as {@code "default"} to signal "framework default". */
    private String orDefault(String value) {
        return (value == null || value.isBlank()) ? DEFAULT : value;
    }
}
