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

package com.societegenerale.failover.core.payload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Envelope that wraps a referential payload with metadata used by the failover mechanism.
 *
 * <p>Each instance represents a single cached entry identified by a ({@code name}, {@code key}) pair.
 * The {@code upToDate} flag signals whether the payload reflects a live response ({@code true})
 * or was served from the failover store ({@code false}). {@code asOf} records when the payload
 * was last written; {@code expireOn} drives eviction via {@link com.societegenerale.failover.core.store.FailoverStore#cleanByExpiry}.
 *
 * @param <T> the type of the business payload
 * @author Anand Manissery
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReferentialPayload<T> {

    /** Logical name of the referential (e.g. entity type or domain identifier). */
    private String name;

    /** Unique key within the referential (e.g. entity ID or request key). */
    private String key;

    /**
     * {@code true} if the payload was obtained from the live source; {@code false} if it was
     * served from the failover store.
     */
    private boolean upToDate;

    /** Timestamp at which this payload was last retried from the golden source. */
    private LocalDateTime asOf;

    /** Timestamp after which this payload is eligible for eviction. */
    private LocalDateTime expireOn;

    /** The actual business payload. */
    private T payload;

    /**
     * Sets the {@code upToDate} flag and returns {@code this} for chaining.
     *
     * @param upToDate {@code true} for a live payload, {@code false} for a failover payload
     * @return this instance
     */
    public ReferentialPayload<T> withUpToDate(boolean upToDate) {
        this.upToDate = upToDate;
        return this;
    }

    /**
     * Creates a shallow copy of this payload, preserving all field values.
     *
     * <p>Intended to be combined with {@link #withUpToDate} to produce a modified copy
     * without mutating the original, e.g. {@code payload.copy().withUpToDate(false)}.
     *
     * @return a new {@link ReferentialPayload} with the same field values
     */
    public ReferentialPayload<T> copy() {
        return new ReferentialPayload<>(this.name, this.key, this.upToDate, this.asOf, this.expireOn, this.payload);
    }

    @Override
    public String toString() {
        return "ReferentialPayload{" +
                "name='" + name + '\'' +
                ", upToDate=" + upToDate +
                ", asOf=" + asOf +
                ", expireOn=" + expireOn +
                ", payload=" + payload +
                '}';
    }
}
