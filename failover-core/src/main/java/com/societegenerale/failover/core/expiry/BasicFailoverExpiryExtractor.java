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

package com.societegenerale.failover.core.expiry;

import java.time.temporal.ChronoUnit;

/**
 * {@link AbstractFailoverExpiryExtractor} that interprets expression strings as plain literals:
 * durations are parsed with {@link Long#parseLong} and units with {@link ChronoUnit#valueOf}.
 *
 * @author Anand Manissery
 */
public class BasicFailoverExpiryExtractor extends AbstractFailoverExpiryExtractor {

    @Override
    protected long resolveExpiryDuration(String expression) {
        return Long.parseLong(expression);
    }

    @Override
    protected ChronoUnit resolveExpiryUnit(String expression) {
        return ChronoUnit.valueOf(expression.toUpperCase());
    }
}
