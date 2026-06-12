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

package com.societegenerale.failover.it.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only state holder injected into both {@link ThirdPartyServiceImpl} and the IT test class.
 *
 * <p>The service owns its data (an immutable referential map); this controller only toggles
 * whether the primary call succeeds or fails. This avoids CGLIB proxy pitfalls: the test
 * manipulates state on the real Spring bean, not on a proxy whose fields are separate from
 * the proxied target's fields.
 *
 * @author Anand Manissery
 */
@Component
public class ThirdPartyServiceController {

    /** When {@code true}, every service method throws to simulate primary unavailability. */
    public final AtomicBoolean primaryFails = new AtomicBoolean(false);

    public void reset() {
        primaryFails.set(false);
    }

    public void simulatePrimaryFailure() {
        primaryFails.set(true);
    }
}