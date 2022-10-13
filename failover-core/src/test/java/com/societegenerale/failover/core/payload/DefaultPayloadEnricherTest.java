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

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.domain.Referential;
import com.societegenerale.failover.domain.ReferentialAware;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * @author Anand Manissery
 */
class DefaultPayloadEnricherTest {

    private static final String FAILOVER_NAME = "failover-name";

    private static final String FAILOVER_KEY = "failover-key";

    private static final Failover FAILOVER = mock(Failover.class);

    private final LocalDateTime now = LocalDateTime.now();

    @Nested
    @DisplayName("Object extended by Referential")
    class ScenariosWithReferential {

        private final DefaultPayloadEnricher<ThirdParty> payloadEnricher = new DefaultPayloadEnricher<>();

        @DisplayName("should enrich the payload from referential metadata")
        @Test
        void shouldEnrichThePayload() {
            ThirdParty thirdParty = new ThirdParty(1L, "TATA", 5);
            ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, thirdParty);

            ReferentialPayload<ThirdParty> result = payloadEnricher.enrich(FAILOVER, referentialPayload);

            assertThat(result.getPayload().getAsOf()).isEqualTo(now);
            assertThat(result.getPayload().getUpToDate()).isTrue();
        }

        @Data
        @EqualsAndHashCode(callSuper = true)
        @AllArgsConstructor
        class ThirdParty extends Referential {
            private Long id;
            private String name;
            private int score;
        }
    }

    @Nested
    @DisplayName("Object extended by ReferentialAware")
    class ScenariosWithReferentialAware {

        private final DefaultPayloadEnricher<ThirdParty> payloadEnricher = new DefaultPayloadEnricher<>();

        @DisplayName("should enrich the payload from referential metadata")
        @Test
        void shouldEnrichThePayload() {
            ThirdParty thirdParty = new ThirdParty(1L, "TATA", 5);
            ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, thirdParty);

            ReferentialPayload<ThirdParty> result = payloadEnricher.enrich(FAILOVER, referentialPayload);

            assertThat(result.getPayload().getAsOf()).isEqualTo(now);
            assertThat(result.getPayload().isUpToDate()).isTrue();
        }

        @Data
        @AllArgsConstructor
        class ThirdParty implements ReferentialAware {
            private Long id;
            private String name;
            private int score;

            private boolean isUpToDate;
            private LocalDateTime asOf;

            public ThirdParty(Long id, String name, int score) {
                this.id = id;
                this.name = name;
                this.score = score;
            }

            @Override
            public void setUpToDate(Boolean upToDate) {
                this.isUpToDate = upToDate;
            }

            @Override
            public void setAsOf(LocalDateTime asOf) {
                this.asOf = asOf;
            }
        }
    }

    @Nested
    @DisplayName("Payload is not a Referential Or ReferentialAware")
    class ScenariosWithoutReferentialOrReferentialAware {

        private final DefaultPayloadEnricher<ThirdParty> payloadEnricher = new DefaultPayloadEnricher<>();

        @DisplayName("should not enrich the payload from referential metadata")
        @Test
        void shouldEnrichThePayload() {
            ThirdParty thirdParty = new ThirdParty(1L, "TATA", 5);
            ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, thirdParty);

            ReferentialPayload<ThirdParty> result = payloadEnricher.enrich(FAILOVER, referentialPayload);

            assertThat(result.getPayload().getAsOf()).isNull();
            assertThat(result.getPayload().getUpToDate()).isNull();
        }

        @Data
        @AllArgsConstructor
        class ThirdParty {
            private Long id;
            private String name;
            private int score;

            private Boolean upToDate;
            private LocalDateTime asOf;

            public ThirdParty(Long id, String name, int score) {
                this.id = id;
                this.name = name;
                this.score = score;
            }
        }
    }

    @Nested
    @DisplayName("Payload is null")
    class ScenariosWhenPayloadIsNull{

        private final DefaultPayloadEnricher<String> payloadEnricher = new DefaultPayloadEnricher<>();

        @DisplayName("should enrich the payload from referential metadata")
        @Test
        void shouldReturnThePayloadAsItIsWhenPayloadIsNull() {

            ReferentialPayload<String> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, null);

            ReferentialPayload<String> result = payloadEnricher.enrich(FAILOVER, referentialPayload);

            assertThat(result).isEqualTo(referentialPayload);
        }
    }

}