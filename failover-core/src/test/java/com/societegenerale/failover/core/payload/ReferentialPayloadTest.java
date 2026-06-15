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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/// @author Anand Manissery
class ReferentialPayloadTest {

    private final static Instant NOW = Instant.now();

    private final ReferentialPayload<String> referentialPayload = new ReferentialPayload<>("NAME", "KEY", true, NOW, NOW, "PAYLOAD");

    @Test
    @DisplayName("should not contain key details in to string")
    void shouldNotContainKeyDetailsInToString() {
        String toString  = referentialPayload.toString();
        assertThat(toString).doesNotContain("KEY");
    }

    @Test
    @DisplayName("toString summarises the non-key fields")
    void toStringSummarisesNonKeyFields() {
        assertThat(referentialPayload.toString())
                .startsWith("ReferentialPayload{")
                .contains("name='NAME'")
                .contains("upToDate=true")
                .contains("payload=PAYLOAD");
    }

    @Test
    @DisplayName("should have default constructor for jackson parsing")
    void shouldHaveDefaultConstructorForJacksonParsing() {
        var localReferentialPayload = new ReferentialPayload<String>();
        localReferentialPayload.setName("NAME");
        localReferentialPayload.setKey("KEY");
        localReferentialPayload.setUpToDate(true);
        localReferentialPayload.setAsOf(NOW);
        localReferentialPayload.setExpireOn(NOW);
        localReferentialPayload.setPayload("PAYLOAD");
        assertThat(localReferentialPayload).isEqualTo(referentialPayload);
    }
}