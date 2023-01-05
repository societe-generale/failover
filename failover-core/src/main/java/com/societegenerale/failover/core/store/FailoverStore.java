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

package com.societegenerale.failover.core.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * @author Anand Manissery
 */
public interface FailoverStore<T> {
    void store(ReferentialPayload<T> referentialPayload);

    void delete(ReferentialPayload<T> referentialPayload);

    Optional<ReferentialPayload<T>> find(String name, String key);

    void cleanByExpiry(LocalDateTime expiry);
}
