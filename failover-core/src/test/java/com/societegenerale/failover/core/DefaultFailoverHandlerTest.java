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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.expiry.ExpiryPolicy;
import com.societegenerale.failover.core.key.DefaultKeyGenerator;
import com.societegenerale.failover.core.key.FailoverKeyGenerator;
import com.societegenerale.failover.core.key.KeyGenerator;
import com.societegenerale.failover.core.key.KeyGeneratorLookup;
import com.societegenerale.failover.core.payload.DefaultPayloadEnricher;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.domain.Referential;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class DefaultFailoverHandlerTest {

    private static final String FAILOVER_NAME = "failover-name";

    private final KeyGenerator keyGenerator = new DefaultKeyGenerator();

    private final Instant now = Instant.now();

    @Mock
    private Failover failover;

    @Mock
    private Throwable cause;

    @Mock
    private FailoverClock clock;

    @Mock
    private FailoverStore<ThirdParty> failoverStore;

    @Mock
    private ExpiryPolicy<ThirdParty> expiryPolicy;

    private final PayloadEnricher<ThirdParty> payloadEnricher = new DefaultPayloadEnricher<>();

    private DefaultFailoverHandler<ThirdParty> defaultFailoverHandler;

    @BeforeEach
    void setUp() {
        lenient().when(failover.name()).thenReturn(FAILOVER_NAME);
        lenient().when(failover.domain()).thenReturn("");
        defaultFailoverHandler = new DefaultFailoverHandler<>(keyGenerator, clock, failoverStore, expiryPolicy, payloadEnricher);
    }

    @Test
    @DisplayName("should store the referential payload")
    void shouldStoreTheReferential() {
        var thirdParty = new ThirdParty(1L, "Tata", 1);
        var referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", true, now, now, thirdParty);
        given(clock.now()).willReturn(now);
        given(expiryPolicy.computeExpiry(failover)).willReturn(now);

        ThirdParty result = defaultFailoverHandler.store(failover, List.of(1L), thirdParty);

        assertThat(result).isEqualTo(thirdParty);
        assertThat(result.getAsOf()).isEqualTo(now);
        assertThat(result.getUpToDate()).isTrue();
        verify(failoverStore).store(referentialPayload);
    }

    @Test
    @DisplayName("should recover the referential payload when not expired")
    void shouldRecoverTheReferentialWhenNotExpired() {
        var thirdParty = new ThirdParty(1L, "Tata", 1);
        var referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, thirdParty);
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(of(referentialPayload));

        ThirdParty result = defaultFailoverHandler.recover(failover, List.of(1L), ThirdParty.class, cause);

        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(thirdParty);
        assertThat(result.getAsOf()).isEqualTo(now);
        assertThat(result.getUpToDate()).isFalse();
        verify(failoverStore).find(FAILOVER_NAME, "1");
        verify(failoverStore, never()).delete(referentialPayload);
        verify(expiryPolicy).isExpired(failover, referentialPayload);
    }

    @Test
    @DisplayName("should return null when no referential payload found for the given name and key")
    void shouldReturnNullWhenReferentialIsNotFound() {
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(Optional.empty());

        ThirdParty result = defaultFailoverHandler.recover(failover, List.of(1L), ThirdParty.class, cause);

        assertThat(result).isNull();
        verify(failoverStore).find(FAILOVER_NAME, "1");
        verify(failoverStore, never()).delete(any());
        verify(expiryPolicy, never()).isExpired(any(), any());
    }

    @Test
    @DisplayName("should return null when referential payload is expired")
    void shouldReturnNullWhenReferentialIsExpired() {
        var thirdParty = new ThirdParty(1L, "Tata", 1);
        var referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, thirdParty);
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(of(referentialPayload));
        given(expiryPolicy.isExpired(failover, referentialPayload)).willReturn(true);

        ThirdParty result = defaultFailoverHandler.recover(failover, List.of(1L), ThirdParty.class, cause);

        assertThat(result).isNull();
        verify(failoverStore).find(FAILOVER_NAME, "1");
        verify(failoverStore).delete(referentialPayload);
        verify(expiryPolicy).isExpired(failover, referentialPayload);
    }

    @Test
    @DisplayName("should enrich metadata on Referential payload with exception info when recovering")
    void shouldEnrichMetadataOnReferentialPayloadWithExceptionInfoWhenRecovering() {
        var cause = new RuntimeException("upstream-failure");
        var thirdParty = new ThirdParty(1L, "Tata", 1);
        var referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, thirdParty);
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(of(referentialPayload));

        ThirdParty result = defaultFailoverHandler.recover(failover, List.of(1L), ThirdParty.class, cause);

        assertThat(result).isNotNull();
        assertThat(result.getMetadata().getInfo())
                .containsEntry("exception-name", RuntimeException.class.getName())
                .containsEntry("cause", "upstream-failure");
    }

    @Test
    @DisplayName("should return null without NPE when payload is null")
    void shouldReturnNullWithoutNpeWhenPayloadIsNull() {
        // null guard must skip clock/expiryPolicy/store — no stubs needed
        ThirdParty result = defaultFailoverHandler.store(failover, List.of(1L), null);

        assertThat(result).isNull();
        verify(failoverStore, never()).store(any());
    }

    @Test
    @DisplayName("should execute clean")
    void shouldExecuteClean() {
        given(clock.now()).willReturn(now);
        defaultFailoverHandler.clean();
        verify(failoverStore).cleanByExpiry(now);
    }

    @Test
    @DisplayName("shared domain: recover via failover-B finds payload stored by failover-A")
    void shouldRecoverFromSharedDomainStoreWhenStoredByDifferentFailoverWithSameDomain() {
        String domain = "tp-shared";
        Failover failoverA = mock(Failover.class);
        Failover failoverB = mock(Failover.class);
        given(failoverA.name()).willReturn("tp-by-id");
        given(failoverA.domain()).willReturn(domain);
        given(failoverA.keyGenerator()).willReturn("");
        given(failoverB.name()).willReturn("tp-list");
        given(failoverB.domain()).willReturn(domain);
        given(failoverB.keyGenerator()).willReturn("");

        ExpiryPolicy<ThirdParty> policy = mock(ExpiryPolicy.class);
        given(clock.now()).willReturn(now);
        given(policy.computeExpiry(failoverA)).willReturn(now.plusSeconds(3600));
        given(policy.isExpired(eq(failoverB), any())).willReturn(false);

        var realKeyGen = new FailoverKeyGenerator(new DefaultKeyGenerator(), mock(KeyGeneratorLookup.class));
        var testStore = new TestFailoverStore<ThirdParty>();
        var handler = new DefaultFailoverHandler<>(realKeyGen, clock, testStore, policy, new DefaultPayloadEnricher<>());

        handler.store(failoverA, List.of("1"), new ThirdParty(1L, "Tata", 1));

        ThirdParty recovered = handler.recover(failoverB, List.of("1"), ThirdParty.class, cause);

        assertThat(recovered).isNotNull();
        assertThat(recovered.getId()).isEqualTo(1L);
        assertThat(recovered.getName()).isEqualTo("Tata");
    }

    @Test
    @DisplayName("recoverAll: returns payload for all non-expired entries")
    void shouldRecoverAllReturnPayloadsForAllNonExpiredEntries() {
        var tp1 = new ThirdParty(1L, "Tata", 1);
        var tp2 = new ThirdParty(2L, "Bata", 2);
        var rp1 = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, tp1);
        var rp2 = new ReferentialPayload<>(FAILOVER_NAME, "2", false, now, now, tp2);
        given(failoverStore.findAll(FAILOVER_NAME)).willReturn(List.of(rp1, rp2));
        given(expiryPolicy.isExpired(failover, rp1)).willReturn(false);
        given(expiryPolicy.isExpired(failover, rp2)).willReturn(false);

        List<ThirdParty> result = defaultFailoverHandler.recoverAll(failover, List.of(), ThirdParty.class, cause);

        assertThat(result).containsExactly(tp1, tp2);
    }

    @Test
    @DisplayName("recoverAll: deletes expired entry and returns null in its place")
    void shouldRecoverAllDeleteExpiredEntryAndReturnNullForIt() {
        var tp1 = new ThirdParty(1L, "Tata", 1);
        var tp2 = new ThirdParty(2L, "Bata", 2);
        var rp1 = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, tp1);
        var rp2 = new ReferentialPayload<>(FAILOVER_NAME, "2", false, now, now, tp2);
        given(failoverStore.findAll(FAILOVER_NAME)).willReturn(List.of(rp1, rp2));
        given(expiryPolicy.isExpired(failover, rp1)).willReturn(false);
        given(expiryPolicy.isExpired(failover, rp2)).willReturn(true);

        List<ThirdParty> result = defaultFailoverHandler.recoverAll(failover, List.of(), ThirdParty.class, cause);

        assertThat(result).hasSize(2).first().isEqualTo(tp1);
        assertThat(result).element(1).isNull();
        verify(failoverStore).delete(rp2);
    }

    @Test
    @DisplayName("recoverAll: returns empty list when store has no entries")
    void shouldReturnEmptyListWhenStoreHasNoEntriesForRecoverAll() {
        given(failoverStore.findAll(FAILOVER_NAME)).willReturn(List.of());

        List<ThirdParty> result = defaultFailoverHandler.recoverAll(failover, List.of(), ThirdParty.class, cause);

        assertThat(result).isEmpty();
    }

    static class TestFailoverStore<T> implements FailoverStore<T> {
        private final Map<String, ReferentialPayload<T>> store = new ConcurrentHashMap<>();

        @Override
        public void store(ReferentialPayload<T> p) throws FailoverStoreException {
            store.put(p.getName() + "##" + p.getKey(), p.copy());
        }

        @Override
        public Optional<ReferentialPayload<T>> find(String name, String key) throws FailoverStoreException {
            return Optional.ofNullable(store.get(name + "##" + key)).map(ReferentialPayload::copy);
        }

        @Override
        public List<ReferentialPayload<T>> findAll(String name) throws FailoverStoreException {
            return store.entrySet().stream().filter(e->
                e.getKey().startsWith(name+"##")
            ).map(e-> e.getValue().copy()).toList();
        }

        @Override
        public void delete(ReferentialPayload<T> p) throws FailoverStoreException {
            store.remove(p.getName() + "##" + p.getKey());
        }

        @Override
        public void cleanByExpiry(Instant expiry) throws FailoverStoreException {
            store.values().removeIf(p -> p.getExpireOn().isBefore(expiry));
        }
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
