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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;

import java.util.List;

/**
 * Core handler for failover lifecycle operations: storing payloads, recovering them on failure,
 * and cleaning up expired entries.
 *
 * @param <T> the type of the payload managed by this handler
 * @author Anand Manissery
 */
public interface FailoverHandler<T> {

    /**
     * Stores the payload for later recovery.
     *
     * @param failover annotation metadata for the failover point
     * @param args     method arguments used to derive the store key
     * @param payload  the result to store
     * @return the stored payload
     */
    T store(Failover failover, List<Object> args, T payload);

    /**
     * Recovers a previously stored payload after a failure.
     *
     * @param failover  annotation metadata for the failover point
     * @param args      method arguments used to derive the lookup key
     * @param clazz     expected return type
     * @param throwable the exception that triggered recovery
     * @return the recovered payload, or {@code null} if not found or expired
     */
    T recover(Failover failover, List<Object> args, Class<T> clazz, Throwable throwable);

    /** Removes all expired entries from the failover store. */
    void clean();
}
