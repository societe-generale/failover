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
import com.societegenerale.failover.domain.Metadata;
import com.societegenerale.failover.domain.Referential;
import com.societegenerale.failover.domain.ReferentialAware;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * @author Anand Manissery
 */
class DefaultPayloadEnricherTest {

    private static final String FAILOVER_NAME = "failover-name";
    private static final String FAILOVER_KEY = "failover-key";
    private static final Failover FAILOVER = mock(Failover.class);

    private final Instant now = Instant.now();

    // -----------------------------------------------------------------------
    // Shared payload stubs
    // -----------------------------------------------------------------------

    @Data
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    static class ReferentialThirdParty extends Referential {
        private Long id;
        private String name;
        private int score;
    }

    @Data
    @AllArgsConstructor
    static class ReferentialAwareThirdParty implements ReferentialAware {
        private Long id;
        private String name;
        private int score;

        private Boolean upToDate;
        private Instant asOf;
        private Metadata metadata;

        ReferentialAwareThirdParty(Long id, String name, int score) {
            this.id = id;
            this.name = name;
            this.score = score;
        }

        @Override public void setUpToDate(Boolean upToDate) { this.upToDate = upToDate; }
        @Override public void setAsOf(Instant asOf) { this.asOf = asOf; }
        @Override public void setMetadata(Metadata metadata) { this.metadata = metadata; }
    }

    @Data
    @AllArgsConstructor
    static class PlainThirdParty {
        private Long id;
        private String name;
        private int score;
        private Boolean upToDate;
        private Instant asOf;

        PlainThirdParty(Long id, String name, int score) {
            this.id = id;
            this.name = name;
            this.score = score;
        }
    }

    // -----------------------------------------------------------------------
    // enrichOnStore
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("enrichOnStore")
    class EnrichOnStore {

        @Nested
        @DisplayName("Referential payload")
        class WithReferential {

            private final DefaultPayloadEnricher<ReferentialThirdParty> enricher = new DefaultPayloadEnricher<>();

            @Test
            @DisplayName("sets upToDate and asOf from envelope")
            void setsUpToDateAndAsOf() {
                var tp = new ReferentialThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, tp);

                var result = enricher.enrichOnStore(FAILOVER, ReferentialThirdParty.class, rp);

                assertThat(result.getPayload().getAsOf()).isEqualTo(now);
                assertThat(result.getPayload().getUpToDate()).isTrue();
            }

            @Test
            @DisplayName("does not populate metadata (no cause on store)")
            void doesNotPopulateMetadata() {
                var tp = new ReferentialThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, tp);

                enricher.enrichOnStore(FAILOVER, ReferentialThirdParty.class, rp);

                assertThat(tp.getMetadata().getInfo()).isEmpty();
            }

            @Test
            @DisplayName("returns the same referential payload instance")
            void returnsSameInstance() {
                var tp = new ReferentialThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, tp);

                var result = enricher.enrichOnStore(FAILOVER, ReferentialThirdParty.class, rp);

                assertThat(result).isSameAs(rp);
            }
        }

        @Nested
        @DisplayName("ReferentialAware payload")
        class WithReferentialAware {

            private final DefaultPayloadEnricher<ReferentialAwareThirdParty> enricher = new DefaultPayloadEnricher<>();

            @Test
            @DisplayName("sets upToDate and asOf from envelope")
            void setsUpToDateAndAsOf() {
                var tp = new ReferentialAwareThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, tp);

                var result = enricher.enrichOnStore(FAILOVER, ReferentialAwareThirdParty.class, rp);

                assertThat(result.getPayload().getAsOf()).isEqualTo(now);
                assertThat(result.getPayload().getUpToDate()).isTrue();
            }

            @Test
            @DisplayName("does not populate metadata (no cause on store)")
            void doesNotPopulateMetadata() {
                var tp = new ReferentialAwareThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, tp);

                enricher.enrichOnStore(FAILOVER, ReferentialAwareThirdParty.class, rp);

                assertThat(tp.getMetadata()).isNull();
            }
        }

        @Nested
        @DisplayName("Plain payload (not Referential or ReferentialAware)")
        class WithPlainPayload {

            private final DefaultPayloadEnricher<PlainThirdParty> enricher = new DefaultPayloadEnricher<>();

            @Test
            @DisplayName("does not modify the payload")
            void doesNotModifyPayload() {
                var tp = new PlainThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, tp);

                enricher.enrichOnStore(FAILOVER, PlainThirdParty.class, rp);

                assertThat(tp.getUpToDate()).isNull();
                assertThat(tp.getAsOf()).isNull();
            }
        }

        @Nested
        @DisplayName("Null payload")
        class WithNullPayload {

            private final DefaultPayloadEnricher<String> enricher = new DefaultPayloadEnricher<>();

            @Test
            @DisplayName("returns the envelope unchanged when payload is null")
            void returnsEnvelopeUnchanged() {
                var rp = new ReferentialPayload<String>(FAILOVER_NAME, FAILOVER_KEY, true, now, now, null);

                var result = enricher.enrichOnStore(FAILOVER, String.class, rp);

                assertThat(result).isEqualTo(rp);
            }
        }
    }

    // -----------------------------------------------------------------------
    // enrichOnRecover
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("enrichOnRecover")
    class EnrichOnRecover {

        @Nested
        @DisplayName("Referential payload")
        class WithReferential {

            private final DefaultPayloadEnricher<ReferentialThirdParty> enricher = new DefaultPayloadEnricher<>();

            @Test
            @DisplayName("sets upToDate and asOf from envelope")
            void setsUpToDateAndAsOf() {
                var tp = new ReferentialThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, tp);

                var result = enricher.enrichOnRecover(FAILOVER, ReferentialThirdParty.class, rp, null);

                assertThat(result.getPayload().getAsOf()).isEqualTo(now);
                assertThat(result.getPayload().getUpToDate()).isFalse();
            }

            @Test
            @DisplayName("populates metadata with exception info when cause is present")
            void populatesMetadataWithCause() {
                var cause = new RuntimeException("upstream down");
                var tp = new ReferentialThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, tp);

                enricher.enrichOnRecover(FAILOVER, ReferentialThirdParty.class, rp, cause);

                var info = tp.getMetadata().getInfo();
                assertThat(info).containsEntry("exception-name", RuntimeException.class.getName())
                                .containsEntry("cause", "upstream down");
            }

            @Test
            @DisplayName("does not modify metadata when cause is null")
            void doesNotModifyMetadataWithoutCause() {
                var tp = new ReferentialThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, tp);

                enricher.enrichOnRecover(FAILOVER, ReferentialThirdParty.class, rp, null);

                assertThat(tp.getMetadata().getInfo()).isEmpty();
            }

            @Test
            @DisplayName("returns the same envelope instance")
            void returnsSameInstance() {
                var tp = new ReferentialThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, tp);

                var result = enricher.enrichOnRecover(FAILOVER, ReferentialThirdParty.class, rp, null);

                assertThat(result).isSameAs(rp);
            }
        }

        @Nested
        @DisplayName("ReferentialAware payload")
        class WithReferentialAware {

            private final DefaultPayloadEnricher<ReferentialAwareThirdParty> enricher = new DefaultPayloadEnricher<>();

            @Test
            @DisplayName("sets upToDate and asOf from envelope")
            void setsUpToDateAndAsOf() {
                var tp = new ReferentialAwareThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, tp);

                var result = enricher.enrichOnRecover(FAILOVER, ReferentialAwareThirdParty.class, rp, null);

                assertThat(result.getPayload().getAsOf()).isEqualTo(now);
                assertThat(result.getPayload().getUpToDate()).isFalse();
            }

            @Test
            @DisplayName("populates metadata with exception info when cause is present")
            void populatesMetadataWithCause() {
                var cause = new IllegalStateException("circuit open");
                var tp = new ReferentialAwareThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, tp);

                enricher.enrichOnRecover(FAILOVER, ReferentialAwareThirdParty.class, rp, cause);

                var metadata = tp.getMetadata();
                assertThat(metadata).isNotNull();
                assertThat(metadata.getInfo())
                        .containsEntry("exception-name", IllegalStateException.class.getName())
                        .containsEntry("cause", "circuit open");
            }

            @Test
            @DisplayName("does not set metadata when cause is null")
            void doesNotSetMetadataWithoutCause() {
                var tp = new ReferentialAwareThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, tp);

                enricher.enrichOnRecover(FAILOVER, ReferentialAwareThirdParty.class, rp, null);

                assertThat(tp.getMetadata()).isNull();
            }
        }

        @Nested
        @DisplayName("Plain payload (not Referential or ReferentialAware)")
        class WithPlainPayload {

            private final DefaultPayloadEnricher<PlainThirdParty> enricher = new DefaultPayloadEnricher<>();

            @Test
            @DisplayName("does not modify the payload even with a cause")
            void doesNotModifyPayload() {
                var tp = new PlainThirdParty(1L, "TATA", 5);
                var rp = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, tp);

                enricher.enrichOnRecover(FAILOVER, PlainThirdParty.class, rp, new RuntimeException("boom"));

                assertThat(tp.getUpToDate()).isNull();
                assertThat(tp.getAsOf()).isNull();
            }
        }

        @Nested
        @DisplayName("Null payload inside envelope")
        class WithNullPayload {

            private final DefaultPayloadEnricher<String> enricher = new DefaultPayloadEnricher<>();

            @Test
            @DisplayName("returns the envelope unchanged when payload is null")
            void returnsEnvelopeUnchanged() {
                var rp = new ReferentialPayload<String>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, null);

                var result = enricher.enrichOnRecover(FAILOVER, String.class, rp, new RuntimeException("err"));

                assertThat(result).isSameAs(rp);
            }
        }

        @Nested
        @DisplayName("Null referentialPayload")
        class WithNullReferentialPayload {

            private final DefaultPayloadEnricher<String> enricher = new DefaultPayloadEnricher<>();

            @Test
            @DisplayName("returns new empty ReferentialPayload without NPE")
            void returnsNewEmptyPayloadWithoutNpe() {
                var result = enricher.enrichOnRecover(FAILOVER, String.class, null, new RuntimeException("err"));

                assertThat(result).isNotNull();
                assertThat(result.getPayload()).isNull();
            }

            @Test
            @DisplayName("does not NPE when overridden extractPayload returns a default Referential payload for null envelope")
            void doesNotNpeWhenExtractPayloadReturnsDefaultForNullEnvelope() {
                // Enricher whose extractPayload() supplies a default payload even when envelope is empty
                var enricher = new DefaultPayloadEnricher<ReferentialThirdParty>() {
                    @Override
                    protected ReferentialThirdParty extractPayload(
                            Class<ReferentialThirdParty> clazz,
                            ReferentialPayload<ReferentialThirdParty> rp) {
                        var p = rp.getPayload();
                        return p != null ? p : new ReferentialThirdParty(0L, "default", 0);
                    }
                };

                // Before fix: passes original null to enrichPayloadInfo → NPE on null.isUpToDate()
                // After fix: passes rPayload (non-null) to enrichPayloadInfo → completes without NPE
                assertThatCode(() ->
                        enricher.enrichOnRecover(FAILOVER, ReferentialThirdParty.class, null, new RuntimeException("err")))
                        .doesNotThrowAnyException();
            }
        }
    }
}
