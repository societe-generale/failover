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

package com.societegenerale.failover.core.key;

import com.societegenerale.failover.annotations.Failover;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverKeyGeneratorTest {

    private static final String KEY_GEN_NAME = "key-gen-X";

    private static final String FAILOVER_NAME = "failover-X";

    private static final List<Object> ARGS = List.of("args-X");

    private static final String KEY = "key-X";

    private static final String FINAL_KEY = UUID.nameUUIDFromBytes((FAILOVER_NAME + ":" + KEY).getBytes(UTF_8)).toString();

    private static final String FINAL_KEY_COPY = "ab731e45-8d4d-330c-b9a8-00651916d239";

    @Mock
    private Failover failover;

    @Mock
    private KeyGenerator defaultKeyGenerator;

    @Mock
    private KeyGenerator customKeyGenerator;

    @Mock
    private KeyGeneratorLookup keyGeneratorLookup;

    private FailoverKeyGenerator failoverKeyGenerator;

    @BeforeEach
    void setUp() {
        lenient().when(failover.name()).thenReturn(FAILOVER_NAME);
        lenient().when(failover.domain()).thenReturn("");
        failoverKeyGenerator = new FailoverKeyGenerator(defaultKeyGenerator, keyGeneratorLookup);
    }

    @Test
    @DisplayName("generate key with default key generator when no key generator specified")
    void generateKeyWithDefaultKeyGeneratorWhenNoKeyGeneratorSpecified() {
        given(failover.keyGenerator()).willReturn("");
        given(defaultKeyGenerator.key(failover, ARGS)).willReturn(KEY);

        var key = failoverKeyGenerator.key(failover, ARGS);

        assertThat(key).isEqualTo(FINAL_KEY);
        verify(defaultKeyGenerator).key(failover, ARGS);
        verify(customKeyGenerator, never()).key(failover, ARGS);
    }

    @Test
    @DisplayName("generate key with custom key generator when no key generator specified")
    void generateKeyWithCustomKeyGeneratorWhenNoKeyGeneratorSpecified() {
        given(failover.keyGenerator()).willReturn(KEY_GEN_NAME);
        given(customKeyGenerator.key(failover, ARGS)).willReturn(KEY);
        given(keyGeneratorLookup.lookup(KEY_GEN_NAME)).willReturn(customKeyGenerator);

        var key = failoverKeyGenerator.key(failover, ARGS);

        assertThat(key).isEqualTo(FINAL_KEY);
        verify(customKeyGenerator).key(failover, ARGS);
        verify(defaultKeyGenerator, never()).key(failover, ARGS);
    }

    @Test
    @DisplayName("should throw exception when no custom key generator found for a given key generator name")
    void shouldThrowExceptionWhenNoCustomKeyGeneratorFoundForAGivenKeyGeneratorName() {
        given(failover.keyGenerator()).willReturn(KEY_GEN_NAME);
        given(keyGeneratorLookup.lookup(KEY_GEN_NAME)).willReturn(null);

        KeyGeneratorNotFoundException exception = assertThrows(KeyGeneratorNotFoundException.class, () -> failoverKeyGenerator.key(failover, ARGS));

        assertThat(exception).isInstanceOf(KeyGeneratorNotFoundException.class);
        assertThat(exception.getMessage()).isEqualTo("No matching KeyGenerator bean found for failover 'failover-X' with key generator qualifier 'key-gen-X'. Neither qualifier match nor bean name match!");
    }

    @Test
    @DisplayName("generate key with fixed length even the args are too long")
    void generateKeyWithFixedLengthEvenTheArgsAreLong() {
        given(failover.keyGenerator()).willReturn("");

        given(defaultKeyGenerator.key(failover, ARGS)).willReturn(KEY);
        var key1 = failoverKeyGenerator.key(failover, ARGS);

        given(defaultKeyGenerator.key(failover, ARGS)).willReturn(KEY.repeat(100));
        var key2 = failoverKeyGenerator.key(failover, ARGS);

        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).hasSameSizeAs(key2).hasSize(FINAL_KEY.length());
        verify(defaultKeyGenerator, times(2)).key(failover, ARGS);
        verify(customKeyGenerator, never()).key(failover, ARGS);
    }

    @Test
    @DisplayName("generate same final key for same failover name and same args always")
    void finalKeyShouldBeSameAsCopy() {
        var newFinalKey = UUID.nameUUIDFromBytes((FAILOVER_NAME + ":" + KEY).getBytes(UTF_8)).toString();
        assertThat(newFinalKey).isEqualTo(FINAL_KEY).isEqualTo(FINAL_KEY_COPY);
    }

    @Test
    @DisplayName("no domain → uses failover name as UUID prefix (backward compat)")
    void noDomainUsesFailoverNameAsUUIDPrefix() {
        given(failover.domain()).willReturn("");
        given(failover.keyGenerator()).willReturn("");
        given(defaultKeyGenerator.key(failover, ARGS)).willReturn(KEY);

        var key = failoverKeyGenerator.key(failover, ARGS);

        assertThat(key).isEqualTo(FINAL_KEY);
    }

    @Test
    @DisplayName("same domain + same args → same UUID key across different failover names")
    void sameDomainAndSameArgsShouldProduceSameUUID() {
        String domain = "shared-domain";
        Failover failoverA = mock(Failover.class);
        Failover failoverB = mock(Failover.class);
        lenient().when(failoverA.name()).thenReturn("tp-by-id"); // not called when domain is set
        given(failoverA.domain()).willReturn(domain);
        given(failoverA.keyGenerator()).willReturn("");
        lenient().when(failoverB.name()).thenReturn("tp-list"); // not called when domain is set
        given(failoverB.domain()).willReturn(domain);
        given(failoverB.keyGenerator()).willReturn("");
        given(defaultKeyGenerator.key(failoverA, ARGS)).willReturn(KEY);
        given(defaultKeyGenerator.key(failoverB, ARGS)).willReturn(KEY);

        String keyA = failoverKeyGenerator.key(failoverA, ARGS);
        String keyB = failoverKeyGenerator.key(failoverB, ARGS);

        assertThat(keyA).isEqualTo(keyB);
        assertThat(keyA).isEqualTo(UUID.nameUUIDFromBytes((domain + ":" + KEY).getBytes(UTF_8)).toString());
    }

    @Test
    @DisplayName("different domains + same args → different UUID keys (domain isolation)")
    void differentDomainsAndSameArgsShouldProduceDifferentUUIDs() {
        Failover failoverA = mock(Failover.class);
        Failover failoverB = mock(Failover.class);
        lenient().when(failoverA.name()).thenReturn("tp-by-id"); // not called when domain is set
        given(failoverA.domain()).willReturn("domain-A");
        given(failoverA.keyGenerator()).willReturn("");
        lenient().when(failoverB.name()).thenReturn("tp-list"); // not called when domain is set
        given(failoverB.domain()).willReturn("domain-B");
        given(failoverB.keyGenerator()).willReturn("");
        given(defaultKeyGenerator.key(failoverA, ARGS)).willReturn(KEY);
        given(defaultKeyGenerator.key(failoverB, ARGS)).willReturn(KEY);

        String keyA = failoverKeyGenerator.key(failoverA, ARGS);
        String keyB = failoverKeyGenerator.key(failoverB, ARGS);

        assertThat(keyA).isNotEqualTo(keyB);
    }
}