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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;

import java.util.List;
import java.util.stream.Stream;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CastingUtilsTest {

    static Stream<Object> castablePayloads() {
        return Stream.of(null,"hello", 42, 3.14, true, List.of(1, 2));
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("castablePayloads")
    void shouldReturnSameInstanceWhenCastSucceeds(Object payload) {
        Object result = cast(payload);
        assertThat(result).isSameAs(payload);
    }

    @Test
    @DisplayName("should throw class cast exception when types are incompatible")
    void shouldThrowClassCastExceptionWhenTypesAreIncompatible() {
        Object payload = 1;
        assertThatThrownBy(() -> {
            String result = cast(payload);
            result.length();
        }).isInstanceOf(ClassCastException.class);
    }
}