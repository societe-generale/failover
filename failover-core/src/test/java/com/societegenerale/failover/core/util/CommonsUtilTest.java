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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommonsUtil — type-specific null/empty checks")
class CommonsUtilTest {

    @Nested
    @DisplayName("isNullOrEmpty")
    class IsNullOrEmpty {

        @Test
        @DisplayName("null is empty")
        void nullIsEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(null)).isTrue();
        }

        @Test
        @DisplayName("empty collection is empty")
        void emptyCollectionIsEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(List.of())).isTrue();
        }

        @Test
        @DisplayName("collection of only nulls is empty")
        void allNullCollectionIsEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(Arrays.asList(null, null))).isTrue();
        }

        @Test
        @DisplayName("collection with at least one non-null element is NOT empty")
        void collectionWithDataIsNotEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(Arrays.asList(null, "x"))).isFalse();
        }

        @Test
        @DisplayName("empty set is empty")
        void emptySetIsEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(Set.of())).isTrue();
        }

        @Test
        @DisplayName("empty map is empty")
        void emptyMapIsEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(Map.of())).isTrue();
        }

        @Test
        @DisplayName("non-empty map is NOT empty")
        void nonEmptyMapIsNotEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(Map.of("k", "v"))).isFalse();
        }

        @Test
        @DisplayName("zero-length array is empty")
        void emptyArrayIsEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(new Object[0])).isTrue();
        }

        @Test
        @DisplayName("non-empty array is NOT empty")
        void nonEmptyArrayIsNotEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(new Object[]{"x"})).isFalse();
        }

        @Test
        @DisplayName("array of only nulls is NOT empty (length-based, unlike collections)")
        void allNullArrayIsNotEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(new Object[]{null, null})).isFalse();
        }

        @Test
        @DisplayName("empty string is NOT empty (only null counts for non-containers)")
        void emptyStringIsNotEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty("")).isFalse();
        }

        @Test
        @DisplayName("non-null scalar is NOT empty")
        void scalarIsNotEmpty() {
            assertThat(CommonsUtil.isNullOrEmpty(42)).isFalse();
            assertThat(CommonsUtil.isNullOrEmpty("data")).isFalse();
        }
    }

    @Nested
    @DisplayName("isNotNullOrEmpty — inverse of isNullOrEmpty")
    class IsNotNullOrEmpty {

        @Test
        @DisplayName("null is not 'present'")
        void nullIsFalse() {
            assertThat(CommonsUtil.isNotNullOrEmpty(null)).isFalse();
        }

        @Test
        @DisplayName("empty / all-null containers are not 'present'")
        void emptyContainersAreFalse() {
            assertThat(CommonsUtil.isNotNullOrEmpty(List.of())).isFalse();
            assertThat(CommonsUtil.isNotNullOrEmpty(Arrays.asList(null, null))).isFalse();
            assertThat(CommonsUtil.isNotNullOrEmpty(Map.of())).isFalse();
            assertThat(CommonsUtil.isNotNullOrEmpty(new Object[0])).isFalse();
        }

        @Test
        @DisplayName("values with data are 'present'")
        void withDataIsTrue() {
            assertThat(CommonsUtil.isNotNullOrEmpty(List.of("x"))).isTrue();
            assertThat(CommonsUtil.isNotNullOrEmpty(Map.of("k", "v"))).isTrue();
            assertThat(CommonsUtil.isNotNullOrEmpty(new Object[]{"x"})).isTrue();
            assertThat(CommonsUtil.isNotNullOrEmpty("data")).isTrue();
        }
    }
}
