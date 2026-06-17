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

package com.societegenerale.failover.properties;

import com.societegenerale.failover.store.async.RejectionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncExecutorTest {

    private final AsyncExecutor asyncExecutor = new AsyncExecutor();

    @Test
    @DisplayName("defaults to unbounded (limit 0) with DISCARD rejection policy")
    void defaults() {
        assertThat(asyncExecutor.getConcurrencyLimit()).isZero();
        assertThat(asyncExecutor.getRejectionPolicy()).isEqualTo(RejectionPolicy.DISCARD);
    }

    @Test
    @DisplayName("fields are settable")
    void settable() {
        asyncExecutor.setConcurrencyLimit(128);
        asyncExecutor.setRejectionPolicy(RejectionPolicy.ABORT);
        assertThat(asyncExecutor.getConcurrencyLimit()).isEqualTo(128);
        assertThat(asyncExecutor.getRejectionPolicy()).isEqualTo(RejectionPolicy.ABORT);
    }
}
