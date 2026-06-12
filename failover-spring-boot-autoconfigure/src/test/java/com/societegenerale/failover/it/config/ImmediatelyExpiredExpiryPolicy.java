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

package com.societegenerale.failover.it.config;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.expiry.ExpiryPolicy;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Custom {@link ExpiryPolicy} used by the IT tests to prove that the expiry policy extension
 * point is wired into the failover framework correctly.
 *
 * <p>This policy always sets {@code EXPIRE_ON} to one hour <em>in the past</em>, making every
 * stored entry immediately expired. Consequence: when the primary call fails, recovery finds the
 * row in the database but rejects it as expired — returning {@code null}.
 *
 * <p>Bean name: {@code immediatelyExpiredExpiryPolicy}, referenced in
 * {@link com.societegenerale.failover.it.service.ThirdPartyService#fetchOneImmediatelyExpired}.
 *
 * @author Anand Manissery
 */
@Component("immediatelyExpiredExpiryPolicy")
public class ImmediatelyExpiredExpiryPolicy implements ExpiryPolicy<Object> {

    @Override
    public Instant computeExpiry(Failover failover) {
        return Instant.now().minusSeconds(3600);   // always in the past → entry expired on arrival
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<Object> payload) {
        return Instant.now().isAfter(payload.getExpireOn());
    }
}