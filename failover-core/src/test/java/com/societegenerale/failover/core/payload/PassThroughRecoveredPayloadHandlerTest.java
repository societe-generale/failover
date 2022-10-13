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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class PassThroughRecoveredPayloadHandlerTest {

    private final PassThroughRecoveredPayloadHandler payloadHandler = new PassThroughRecoveredPayloadHandler();

    @DisplayName("should always return the same payload")
    @Test
    void shouldAlwaysReturnTheSamePayload() {
        String result = payloadHandler.handle(null, null, String.class, "SOME-PAYLOAD");
        assertThat(result).isEqualTo("SOME-PAYLOAD");
    }

    @DisplayName("should always return the same payload even the payload is null")
    @Test
    void shouldAlwaysReturnTheSamePayloadEvenIfThePayloadIsNull() {
        String result = payloadHandler.handle(null, null, String.class, null);
        assertThat(result).isNull();
    }
}