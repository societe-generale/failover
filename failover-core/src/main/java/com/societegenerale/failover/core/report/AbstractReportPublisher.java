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

package com.societegenerale.failover.core.report;

import com.societegenerale.failover.core.clock.FailoverClock;
import lombok.AllArgsConstructor;

/**
 * @author Anand Manissery
 */
@AllArgsConstructor
public abstract class AbstractReportPublisher implements ReportPublisher {

    private final FailoverClock clock;

    @Override
    public void publish(Metrics metrics) {
        metrics.collect("report-publish-on", clock.now().toString());
        doPublish(metrics);
    }

    public abstract void doPublish(Metrics metrics);
}