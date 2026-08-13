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

package com.societegenerale.failover.dashboard.web;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/**
 * Validation for the instance id carried on both peer-ingest endpoints.
 *
 * <p>The id is the one field of an ingest request the dashboard stores under, keys by, and renders —
 * it reaches {@link com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore}
 * and {@link com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore} as a map
 * key, and the Instances table as cell text and a {@code data-id} attribute. It arrives verbatim over
 * the network, so it is validated here rather than trusted: unbounded length is a heap cost per stored
 * entry, and markup characters are what turn a peer push into injected content in an operator's
 * browser.
 *
 * <p>The accepted shape covers what real ids look like — service/pod names, hostnames, {@code host:port}
 * pairs, UUIDs, the {@code orders-svc:host-1:8080} form the docs use — while excluding every character
 * needed to break out of HTML text or an attribute value. This is the outer layer only; the UI escapes
 * on render regardless, because other rendered fields (referential {@code name}, {@code domain}) come
 * from consumer annotations that legitimately cannot be constrained this way.
 *
 * @author Anand Manissery
 */
final class InstanceId {

    /** Bounds the heap cost of one stored entry; far above any realistic service/pod identifier. */
    static final int MAX_LENGTH = 200;

    /** Letters, digits, and the separators real instance ids use. Deliberately excludes {@code < > " ' &}. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:@/-]+");

    private InstanceId() {
    }

    /**
     * Validates an inbound instance id, rejecting the request if it is unusable.
     *
     * @param instanceId the id as supplied by the pushing peer
     * @return the same id, once validated
     * @throws ResponseStatusException {@code 400 Bad Request} if the id is absent, blank, longer than
     *         {@link #MAX_LENGTH}, or contains characters outside the accepted set
     */
    static String validated(@Nullable String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instanceId is required.");
        }
        if (instanceId.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "instanceId exceeds the maximum length of " + MAX_LENGTH + " characters.");
        }
        if (!VALID.matcher(instanceId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "instanceId may contain only letters, digits and the separators . _ - : @ /");
        }
        return instanceId;
    }
}
