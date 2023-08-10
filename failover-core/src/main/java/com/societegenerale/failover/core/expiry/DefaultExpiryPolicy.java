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

package com.societegenerale.failover.core.expiry;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * @author Anand Manissery
 */
@AllArgsConstructor
public class DefaultExpiryPolicy<T> implements ExpiryPolicy<T> {

    private final FailoverClock clock;

    private final FailoverExpiryExtractor failoverExpiryExtractor;

    @Override
    public LocalDateTime computeExpiry(Failover failover) {
        return clock.now().plus(failoverExpiryExtractor.expiryDuration(failover), failoverExpiryExtractor.expiryUnit(failover));
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<T> referentialPayload) {
        return clock.now().isAfter(referentialPayload.getExpireOn());
    }
}
