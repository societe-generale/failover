/*
 * Copyright 2022-2023, Société Générale All rights reserved.
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

package com.societegenerale.failover.properties;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.societegenerale.failover.properties.FailoverType.BASIC;

/**
 * @author Anand Manissery
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "failover")
public class FailoverProperties {
    /**
     * Whether to enable or disable the failover feature.
     * Default value is 'true'.
     */
    private boolean enabled = true;

    /**
     * Please provide your base package, where you have the business domain.
     * This is to scan for {@com.societegenerale.failover.annotations.Failover} to identify the Failover configurations.
     * This is a mandatory field
     */
    private String packageToScan;

    /**
     * Type of Failover to be specified. Default to 'BASIC'
     * Available options : BASIC, RESILIENCE, CUSTOM
     */
    private FailoverType type = BASIC;

    @NestedConfigurationProperty()
    private Store store = new Store();

    @NestedConfigurationProperty()
    private Scheduler scheduler = new Scheduler();

    public Map<String,String> additionalInfo() {
        Map<String,String> info = new LinkedHashMap<>();
        info.put("enabled", Boolean.toString(enabled));
        info.put("type", type.name());
        info.put("store.type", store.getType().name());
        info.put("store.jdbc.table-prefix", store.getJdbc().getTablePrefix());
        info.put("scheduler.enabled", Boolean.toString(scheduler.isEnabled()));
        info.put("scheduler.report-cron", scheduler.getReportCron());
        info.put("scheduler.cleanup-cron", scheduler.getCleanupCron());
        return info;
    }
}
