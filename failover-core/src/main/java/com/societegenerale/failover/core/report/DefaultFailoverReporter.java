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
import com.societegenerale.failover.core.report.manifest.ManifestInfoExtractor;
import com.societegenerale.failover.core.scanner.FailoverScanner;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.String.format;

/**
 * @author Anand Manissery
 */
public class DefaultFailoverReporter implements FailoverReporter {

    private final ReportPublisher reportPublisher;

    private final FailoverScanner failoverScanner;

    private final Map<String,String> additionalInfo;

    private final FailoverClock clock;

    private final ManifestInfoExtractor manifestInfoExtractor;

    private final LocalDateTime serviceStartTime;

    public DefaultFailoverReporter(ReportPublisher reportPublisher, FailoverScanner failoverScanner, FailoverClock clock, ManifestInfoExtractor manifestInfoExtractor, Map<String, String> additionalInfo) {
        this.reportPublisher = reportPublisher;
        this.failoverScanner = failoverScanner;
        this.additionalInfo = additionalInfo;
        this.clock = clock;
        this.manifestInfoExtractor = manifestInfoExtractor;
        this.serviceStartTime = clock.now();
    }

    @Override
    public void report() {
        Map<String,String> genericInfo = new LinkedHashMap<>();
        genericInfo.put("metrics-as-on", clock.now().toString());
        genericInfo.put("service-start-time", serviceStartTime.toString());
        genericInfo.putAll(additionalInfo);
        genericInfo.putAll(manifestInfoExtractor.extract("failover-core"));
        failoverScanner.findAllFailover().forEach(failover->  {
            Metrics metrics = Metrics.of(format("failover-report-%s", failover.name()))
                    .collect("name", failover.name())
                    .collect("expiry-duration", Long.toString(failover.expiryDuration()))
                    .collect("expiry-unit", failover.expiryUnit().name());
            genericInfo.forEach(metrics::collect);
            reportPublisher.publish(metrics);
        });
    }
}