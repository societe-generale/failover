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

package com.societegenerale.failover.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class ReferentialTest {

    @DisplayName("should have default referential values while instantiating when not set explicitly")
    @Test
    void shouldHaveDefaultReferentialValueWhenNotProvided() {
        ThirdParty thirdParty = new ThirdParty(1L, "TATA", 5);
        assertThat(thirdParty.getUpToDate()).isNull();
        assertThat(thirdParty.getAsOf()).isNull();
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    static class ThirdParty extends Referential {
        private Long id;
        private String name;
        private int score;
    }
}


