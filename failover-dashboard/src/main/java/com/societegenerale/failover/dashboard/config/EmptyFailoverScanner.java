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

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.scanner.FailoverScanner;

import java.util.List;

/**
 * Empty {@link FailoverScanner} used when the dashboard runs <strong>standalone</strong> — as its own Spring Boot
 * app pointed at a remote backend ({@code cluster.mode=prometheus} or {@code shared-store}) with no {@code @Failover}
 * library on the classpath. There are no annotated methods to discover, so the config view is simply empty while the
 * metrics views work from the backend. Wired {@code @ConditionalOnMissingBean}, so the real scanner always wins when
 * the failover library is present.
 *
 * @author Anand Manissery
 */
public class EmptyFailoverScanner implements FailoverScanner {

    @Override
    public Failover findFailoverByName(String name) {
        return null;
    }

    @Override
    public List<Failover> findAllFailover() {
        return List.of();
    }
}
