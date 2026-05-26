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