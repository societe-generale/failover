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

package com.societegenerale.failover.domain;

import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * Base class for referential entities which need to be aware of the failover metadata information.
 *
 * @author Anand Manissery
 * @since 1.0.0
 */
@Data
public abstract class Referential implements Serializable {

    /**
     * Whether the referential value is up-to-date.
     * {@code true} means a live value; {@code false} means it was recovered from the failover store.
     */
    private Boolean upToDate;

    /** The timestamp at which the referential value was captured from the live source. */
    private Instant asOf;

    /** Additional metadata populated during failover recovery. */
    private Metadata metadata = new Metadata();
}
