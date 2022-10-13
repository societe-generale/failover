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

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class ReferentialPayloadTest {

    private final static LocalDateTime NOW = LocalDateTime.now();

    private final ReferentialPayload<String> referentialPayload = new ReferentialPayload<>("NAME", "KEY", true, NOW, NOW, "PAYLOAD");

    @Test
    void shouldNotContainKeyDetailsInToString() {
        String toString  = referentialPayload.toString();
        assertThat(toString).doesNotContain("KEY");
    }

    @Test
    void shouldHaveDefaultConstructorForJacksonParsing() {
        ReferentialPayload<String> localReferentialPayload = new ReferentialPayload<>();
        localReferentialPayload.setName("NAME");
        localReferentialPayload.setKey("KEY");
        localReferentialPayload.setUpToDate(true);
        localReferentialPayload.setAsOf(NOW);
        localReferentialPayload.setExpireOn(NOW);
        localReferentialPayload.setPayload("PAYLOAD");
        assertThat(localReferentialPayload).isEqualTo(referentialPayload);
    }
}