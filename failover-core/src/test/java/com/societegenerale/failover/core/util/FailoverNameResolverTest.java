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

package com.societegenerale.failover.core.util;

import com.societegenerale.failover.annotations.Failover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.societegenerale.failover.core.util.FailoverNameResolver.effectiveName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverNameResolverTest {

    @Mock
    private Failover failover;

    @Test
    @DisplayName("domain blank → returns failover name")
    void domainBlankReturnsFailoverName() {
        given(failover.name()).willReturn("tp-by-id");
        given(failover.domain()).willReturn("");

        assertThat(effectiveName(failover)).isEqualTo("tp-by-id");
    }

    @Test
    @DisplayName("domain set → returns domain")
    void domainSetReturnsDomain() {
        given(failover.domain()).willReturn("tp-shared");

        assertThat(effectiveName(failover)).isEqualTo("tp-shared");
    }

    @Test
    @DisplayName("domain whitespace-only → treated as blank, returns name")
    void domainWhitespaceOnlyReturnsFailoverName() {
        given(failover.name()).willReturn("tp-by-id");
        given(failover.domain()).willReturn("   ");

        assertThat(effectiveName(failover)).isEqualTo("tp-by-id");
    }
}
