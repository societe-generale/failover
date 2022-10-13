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
import com.societegenerale.failover.core.payload.ReferentialPayload;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

import static java.lang.String.format;

/**
 * @author Anand Manissery
 */
@AllArgsConstructor
public class FailoverExpiryPolicy<T> implements ExpiryPolicy<T> {

    private final ExpiryPolicy<T> defaultExpiryPolicy;

    private final ExpiryPolicyLookup<T> expiryPolicyLookup;

    @Override
    public LocalDateTime computeExpiry(Failover failover) {
        return getExpiryPolicy(failover).computeExpiry(failover);
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<T> referentialPayload) {
        return getExpiryPolicy(failover).isExpired(failover, referentialPayload);
    }

    private ExpiryPolicy<T> getExpiryPolicy(Failover failover) {
        if(failover.expiryPolicy().isEmpty()) {
            return defaultExpiryPolicy;
        }
        ExpiryPolicy<T> expiryPolicy = expiryPolicyLookup.lookup(failover.expiryPolicy());
        if(expiryPolicy == null) {
            throw new ExpiryPolicyNotFoundException(format("No matching ExpiryPolicy bean found for failover '%s' with expiry policy qualifier '%s'. Neither qualifier match nor bean name match!", failover.name(), failover.expiryPolicy()));
        }
        return expiryPolicy;
    }
}
