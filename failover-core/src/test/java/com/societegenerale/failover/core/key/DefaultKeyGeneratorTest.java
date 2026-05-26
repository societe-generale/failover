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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;

import static java.lang.Integer.toHexString;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * @author Anand Manissery
 */
class DefaultKeyGeneratorTest {

    private static final Failover FAILOVER = mock(Failover.class);

    private final DefaultKeyGenerator defaultKeyProvider = new DefaultKeyGenerator();

    @Test
    @DisplayName("should return 'no-arg' when argument is null")
    void shouldReturnNoArgWhenArgumentIsNull() {
        String key = defaultKeyProvider.key(FAILOVER, null);
        assertThat(key).isEqualTo("NO-ARG");
    }

    @Test
    @DisplayName("should return 'no-arg' when argument is empty")
    void shouldReturnNoArgWhenArgumentIsEmpty() {
        String key = defaultKeyProvider.key(FAILOVER, new ArrayList<>());
        assertThat(key).isEqualTo("NO-ARG");
    }

    @Test
    @DisplayName("should return the key by concatenating all arguments")
    void shouldReturnTheKeyByConcatenatingAllArguments() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x","y",1L,2,3));
        assertThat(key).isEqualTo("x:y:1:2:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a collection")
    void shouldReturnTheKeyWhenOneArgIsACollection() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", asList(1L,2,3), "y"));
        assertThat(key).isEqualTo("x:1,2,3:y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is an array")
    void shouldReturnTheKeyWhenOneArgIsAnArray() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", new int[]{1,2,3}, "y"));
        assertThat(key).isEqualTo("x:1,2,3:y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a null value")
    void shouldReturnTheKeyWhenOneArgIsNull() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", null, "y"));
        assertThat(key).isEqualTo("x::y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a list with a null value")
    void shouldReturnTheKeyWhenOneArgIsNullInList() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", asList(1L,null,3), "y"));
        assertThat(key).isEqualTo("x:1,,3:y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is an array with a null value")
    void shouldReturnTheKeyWhenOneArgIsNullInAnArray() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", new String[]{"1",null,"3"}, "y"));
        assertThat(key).isEqualTo("x:1,,3:y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a BigDecimal")
    void shouldReturnTheKeyWhenArgContainsBigDecimal() {
        String key = defaultKeyProvider.key(FAILOVER, asList(1L, new BigDecimal(2), "3"));
        assertThat(key).isEqualTo("1:2:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a Boolean")
    void shouldReturnTheKeyWhenArgContainsBoolean() {
        String key = defaultKeyProvider.key(FAILOVER, asList(1L, Boolean.TRUE, "3"));
        assertThat(key).isEqualTo("1:true:3");
    }

    @Test
    @DisplayName("should return the key when the arguments are of multiple types")
    void shouldReturnTheKeyWhenArgContainsMultipleTypes() {
        var one = 1L;
        Long two = 2L;
        var object = new Object();
        String key = defaultKeyProvider.key(FAILOVER, asList(one, two, new BigDecimal(3), "4", object));
        assertThat(key).isEqualTo("1:2:3:4:Object@%s".formatted(toHexString(object.hashCode())));
    }

    @Test
    @DisplayName("should return the key when the argument is of any non primitive Object")
    void shouldReturnTheKeyWhenArgContainsAnyObjectTypesOtherThanPrimitiveType() {
        var object = new Object();
        String key = defaultKeyProvider.key(FAILOVER, asList(1, object, "3"));
        assertThat(key).isEqualTo("1:Object@%s:3".formatted(toHexString(object.hashCode())));
    }
}