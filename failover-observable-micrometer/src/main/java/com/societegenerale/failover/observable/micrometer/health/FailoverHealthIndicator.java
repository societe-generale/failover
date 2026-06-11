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

package com.societegenerale.failover.observable.micrometer.health;

import com.societegenerale.failover.core.observable.scanner.FailoverScanner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the failover framework.
 *
 * <p>Reports {@code DOWN} when the {@link FailoverScanner} found zero
 * {@code @Failover} annotations — a strong signal of misconfiguration (e.g.
 * AOP not wired). Reports {@code UP}
 * otherwise, including the registered failover count as a detail.
 *
 * <p>Active only when {@code spring-boot-actuate} is on the classpath.
 * Register it as a {@code @Bean} and it is automatically picked up by
 * Spring Boot's health endpoint at {@code /actuator/health/failover}.
 *
 * @author Anand Manissery
 */
public class FailoverHealthIndicator implements HealthIndicator {

    private final FailoverScanner scanner;

    /**
     * Creates a health indicator backed by the given scanner.
     *
     * @param scanner scanner whose discovered failover count drives the health status
     */
    public FailoverHealthIndicator(FailoverScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public Health health() {
        int count = scanner.findAllFailover().size();
        if (count == 0) {
            return Health.down()
                .withDetail("registered-failovers", 0)
                .withDetail("reason", "No @Failover annotations discovered. "
                    + "Verify that your service beans are registered in the Spring context.")
                .build();
        }
        return Health.up()
            .withDetail("registered-failovers", count)
            .build();
    }
}
