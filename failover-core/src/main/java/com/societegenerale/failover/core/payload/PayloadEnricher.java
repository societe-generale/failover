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

package com.societegenerale.failover.core.payload;

import com.societegenerale.failover.annotations.Failover;

/**
 * Strategy for enriching payloads before they are stored or after they are recovered.
 *
 * @param <T> the type of the payload to enrich
 * @author Anand Manissery
 */
public interface PayloadEnricher<T> {

    /**
     * Enriches the payload before it is written to the failover store.
     *
     * @param failover           annotation metadata for the failover point
     * @param clazz              expected payload type
     * @param referentialPayload the wrapper holding the payload to enrich
     * @return the enriched referential payload
     */
    ReferentialPayload<T> enrichOnStore(Failover failover, Class<T> clazz, ReferentialPayload<T> referentialPayload);

    /**
     * Enriches the payload after it has been recovered from the failover store.
     *
     * @param failover           annotation metadata for the failover point
     * @param clazz              expected payload type
     * @param referentialPayload the wrapper holding the recovered payload; may be {@code null}
     * @param cause              the exception that triggered recovery
     * @return the enriched referential payload
     */
    ReferentialPayload<T> enrichOnRecover(Failover failover, Class<T> clazz, ReferentialPayload<T> referentialPayload, Throwable cause);
}
