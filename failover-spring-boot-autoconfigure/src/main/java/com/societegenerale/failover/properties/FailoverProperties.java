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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.societegenerale.failover.properties.ExceptionPolicy.RETHROW;
import static com.societegenerale.failover.properties.FailoverType.BASIC;

/**
 * Root configuration properties for the failover framework, bound to the {@code failover} prefix.
 *
 * @author Anand Manissery
 */
@Data
@Validated
@ConfigurationProperties(prefix = "failover")
public class FailoverProperties {
    /**
     * Whether to enable or disable the failover feature.
     * Default value is 'true'.
     */
    private boolean enabled = true;

    /**
     * Type of Failover to be specified. Default to 'BASIC'
     * Available options : BASIC, RESILIENCE, CUSTOM
     */
    private FailoverType type = BASIC;

    /**
     * Type of ExceptionPolicy to be specified. Default to 'RET̈̈HROW'
     * Available options : RET̈̈HROW, NEVER_THROW, CUSTOM
     */
    private ExceptionPolicy exceptionPolicy = RETHROW;

    @NestedConfigurationProperty()
    private Scatter  scatter = new Scatter();

    @NestedConfigurationProperty()
    private Store store = new Store();

    @NestedConfigurationProperty()
    private Scheduler scheduler = new Scheduler();

    /**
     * Returns a flat map of key failover properties for inclusion in startup reports.
     *
     * @return map of property names to their string values
     */
    public Map<String,String> additionalInfo() {
        var info = new LinkedHashMap<String,String>();
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
