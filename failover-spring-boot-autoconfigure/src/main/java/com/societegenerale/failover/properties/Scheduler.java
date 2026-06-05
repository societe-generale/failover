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

/**
 * Scheduler configuration for the failover framework.
 *
 * <p>Controls whether the report and expiry-cleanup schedulers are active
 * and the cron expressions that drive them.
 *
 * @author Anand Manissery
 */
@Data
public class Scheduler {

    /** Whether to enable or disable the failover scheduler feature. Default: {@code true}. */
    private boolean enabled = true;

    /**
     * Cron expression for the report-publisher scheduler.
     * Default: {@code "0 0 0 * * *"} (daily at midnight).
     */
    private String reportCron = "0 0 0 * * *";

    /**
     * Cron expression for the expiry-cleanup scheduler.
     * Default: {@code "0 0 * * * *"} (hourly).
     */
    private String cleanupCron = "0 0 * * * *";
}
