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
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * @author Anand Manissery
 */
class DefaultKeyGeneratorTest {

    private static final String NAME = "failover-name";

    private static final Failover FAILOVER = mock(Failover.class);

    private final DefaultKeyGenerator defaultKeyProvider = new DefaultKeyGenerator();

    @DisplayName("should return 'no-arg' when argument is null")
    @Test
    void shouldReturnNoArgWhenArgumentIsNull() {
        String key = defaultKeyProvider.key(FAILOVER, null);
        assertThat(key).isEqualTo("NO-ARG");
    }

    @DisplayName("should return 'no-arg' when argument is empty")
    @Test
    void shouldReturnNoArgWhenArgumentIsEmpty() {
        String key = defaultKeyProvider.key(FAILOVER, new ArrayList<>());
        assertThat(key).isEqualTo("NO-ARG");
    }

    @DisplayName("should return the key by concatenating all arguments")
    @Test
    void shouldReturnTheKeyByConcatenatingAllArguments() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x","y",1L,2,3));
        assertThat(key).isEqualTo("x:y:1:2:3");
    }

    @DisplayName("should return the key when one of the argument is a collection")
    @Test
    void shouldReturnTheKeyWhenOneArgIsACollection() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", asList(1L,2,3), "y"));
        assertThat(key).isEqualTo("x:1,2,3:y");
    }

    @DisplayName("should return the key when one of the argument is an array")
    @Test
    void shouldReturnTheKeyWhenOneArgIsAnArray() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", new int[]{1,2,3}, "y"));
        assertThat(key).isEqualTo("x:1,2,3:y");
    }

    @DisplayName("should return the key when one of the argument is a null value")
    @Test
    void shouldReturnTheKeyWhenOneArgIsNull() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", null, "y"));
        assertThat(key).isEqualTo("x::y");
    }

    @DisplayName("should return the key when one of the argument is a list with a null value")
    @Test
    void shouldReturnTheKeyWhenOneArgIsNullInList() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", asList(1L,null,3), "y"));
        assertThat(key).isEqualTo("x:1,,3:y");
    }

    @DisplayName("should return the key when one of the argument is an array with a null value")
    @Test
    void shouldReturnTheKeyWhenOneArgIsNullInAnArray() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", new String[]{"1",null,"3"}, "y"));
        assertThat(key).isEqualTo("x:1,,3:y");
    }

    @DisplayName("should return the key when one of the argument is a BigDecimal")
    @Test
    void shouldReturnTheKeyWhenArgContainsBigDecimal() {
        String key = defaultKeyProvider.key(FAILOVER, asList(1L, new BigDecimal(2), "3"));
        assertThat(key).isEqualTo("1:2:3");
    }

    @DisplayName("should return the key when the arguments are of multiple types")
    @Test
    void shouldReturnTheKeyWhenArgContainsMultipleTypes() {
        long one = 1;
        Long two = 2L;
        Object object = new Object();
        String key = defaultKeyProvider.key(FAILOVER, asList(one, two, new BigDecimal(3), "4", object));
        assertThat(key).isEqualTo(format("1:2:3:4:Object@%s", toHexString(object.hashCode())));
    }

    @DisplayName("should return the key when the argument is of any non primitive Object")
    @Test
    void shouldReturnTheKeyWhenArgContainsAnyObjectTypesOtherThanPrimitiveType() {
        Object object = new Object();
        String key = defaultKeyProvider.key(FAILOVER, asList(1, object, "3"));
        assertThat(key).isEqualTo(format("1:Object@%s:3", toHexString(object.hashCode())));
    }
}