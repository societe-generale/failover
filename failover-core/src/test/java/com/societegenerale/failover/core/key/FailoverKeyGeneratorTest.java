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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverKeyGeneratorTest {

    private static final String KEY_GEN_NAME = "key-gen-name";

    private static final List<Object> ARGS = singletonList("some-args");

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
        lenient().when(failover.name()).thenReturn("failover-xyz");
        failoverKeyGenerator = new FailoverKeyGenerator(defaultKeyGenerator, keyGeneratorLookup);
    }

    @Test
    void generateKeyWithDefaultKeyGeneratorWhenNoKeyGeneratorSpecified() {
        given(failover.keyGenerator()).willReturn("");

        failoverKeyGenerator.key(failover, ARGS);

        verify(defaultKeyGenerator).key(failover, ARGS);
        verify(customKeyGenerator, never()).key(failover, ARGS);
    }

    @Test
    void generateKeyWithCustomKeyGeneratorWhenNoKeyGeneratorSpecified() {
        given(failover.keyGenerator()).willReturn(KEY_GEN_NAME);
        given(keyGeneratorLookup.lookup(KEY_GEN_NAME)).willReturn(customKeyGenerator);

        failoverKeyGenerator.key(failover, ARGS);

        verify(customKeyGenerator).key(failover, ARGS);
        verify(defaultKeyGenerator, never()).key(failover, ARGS);
    }

    @Test
    void shouldThrowExceptionWhenNoCustomKeyGeneratorFoundForAGivenKeyGeneratorName() {
        given(failover.keyGenerator()).willReturn(KEY_GEN_NAME);
        given(keyGeneratorLookup.lookup(KEY_GEN_NAME)).willReturn(null);

        KeyGeneratorNotFoundException exception = assertThrows(KeyGeneratorNotFoundException.class, () -> failoverKeyGenerator.key(failover, ARGS));

        assertThat(exception).isInstanceOf(KeyGeneratorNotFoundException.class);
        assertThat(exception.getMessage()).isEqualTo("No matching KeyGenerator bean found for failover 'failover-xyz' with key generator qualifier 'key-gen-name'. Neither qualifier match nor bean name match!");
    }
}