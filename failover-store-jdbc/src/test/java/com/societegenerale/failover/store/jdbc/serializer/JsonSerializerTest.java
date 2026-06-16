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

package com.societegenerale.failover.store.jdbc.serializer;

import com.societegenerale.failover.core.store.FailoverStoreException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSerializerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final JsonSerializer serializer = new JsonSerializer(OBJECT_MAPPER);

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class SamplePayload {
        private String name;
        private int value;
    }

    /** Payload whose field types live in packages NOT on the allowlist ({@code java.time}, {@code java.math}, generic list). */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class NestedPayload {
        private String name;
        private Instant timestamp;     // java.time — not allowlisted
        private BigDecimal amount;     // java.math — not allowlisted
        private List<String> tags;     // generic collection
    }

    @Nested
    @DisplayName("serialize")
    class Serialize {

        @Test
        @DisplayName("should return null when payload is null")
        void nullPayloadReturnsNull() {
            assertThat(serializer.serialize(null)).isNull();
        }

        @Test
        @DisplayName("should serialize a String to a quoted JSON string")
        void stringPayloadSerializesToJsonString() {
            assertThat(serializer.serialize("hello")).isEqualTo("\"hello\"");
        }

        @Test
        @DisplayName("should serialize an Integer to a JSON number")
        void integerPayloadSerializesToJsonNumber() {
            assertThat(serializer.serialize(42)).isEqualTo("42");
        }

        @Test
        @DisplayName("should serialize a POJO to a JSON object string")
        void pojoPayloadSerializesToJsonObject() {
            SamplePayload payload = new SamplePayload("test", 1);
            String result = serializer.serialize(payload);
            assertThat(result).contains("\"name\":\"test\"", "\"value\":1");
        }
    }

    @Nested
    @DisplayName("deserialize")
    class Deserialize {

        @Test
        @DisplayName("should return null when payload is null")
        void nullPayloadReturnsNull() {
            assertThat(serializer.deserialize(null, SamplePayload.class)).isNull();
        }

        @Test
        @DisplayName("should return null when clazz is null")
        void nullClassReturnsNull() {
            Object result = serializer.deserialize("{\"name\":\"test\"}", null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null when both payload and clazz are null")
        void bothNullReturnsNull() {
            Object result = serializer.deserialize(null, null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should deserialize a JSON string back to the original POJO")
        void validJsonDeserializesToPojo() {
            SamplePayload original = new SamplePayload("test", 99);
            String json = serializer.serialize(original);
            SamplePayload result = serializer.deserialize(json, SamplePayload.class);
            assertThat(result).isEqualTo(original);
        }

        @Test
        @DisplayName("should round-trip a String value")
        void stringRoundTrip() {
            String json = serializer.serialize("hello");
            assertThat(serializer.deserialize(json, String.class)).isEqualTo("hello");
        }

        @Test
        @DisplayName("should throw an exception when JSON is malformed")
        void malformedJsonThrowsException() {
            assertThatThrownBy(() -> serializer.deserialize("{not-valid-json", SamplePayload.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("toClassName")
    class ToClassName {

        @Test
        @DisplayName("should return null when payload is null")
        void nullPayloadReturnsNull() {
            assertThat(serializer.toClassName(null)).isNull();
        }

        @Test
        @DisplayName("should return fully qualified class name for a String")
        void stringPayloadReturnsStringClassName() {
            assertThat(serializer.toClassName("hello")).isEqualTo("java.lang.String");
        }

        @Test
        @DisplayName("should return fully qualified class name for an Integer")
        void integerPayloadReturnsIntegerClassName() {
            assertThat(serializer.toClassName(42)).isEqualTo("java.lang.Integer");
        }

        @Test
        @DisplayName("should return fully qualified class name for a POJO")
        void pojoPayloadReturnsPojoClassName() {
            assertThat(serializer.toClassName(new SamplePayload("x", 1)))
                    .isEqualTo(SamplePayload.class.getName());
        }
    }

    @Nested
    @DisplayName("toClass(String)")
    class ToClassFromString {

        @Test
        @DisplayName("should return null when className is null")
        void nullClassNameReturnsNull() {
            assertThat(serializer.toClass(null)).isNull();
        }

        @Test
        @DisplayName("should return String.class for 'java.lang.String'")
        void knownClassNameReturnsClass() {
            assertThat(serializer.<String>toClass("java.lang.String")).isEqualTo(String.class);
        }

        @Test
        @DisplayName("should return the POJO class for its fully qualified name")
        void pojoClassNameReturnsPOJOClass() {
            assertThat(serializer.<SamplePayload>toClass(SamplePayload.class.getName()))
                    .isEqualTo(SamplePayload.class);
        }

        @Test
        @DisplayName("should throw FailoverStoreException when class name does not exist on classpath")
        void unknownClassNameThrows() {
            assertThatThrownBy(() -> serializer.toClass("com.example.NonExistentClass"))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining("com.example.NonExistentClass")
                    .hasCauseInstanceOf(ClassNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("toClass(String) with allowlist")
    class ToClassWithAllowlist {

        private final JsonSerializer restricted = new JsonSerializer(OBJECT_MAPPER,
                List.of("java.lang.String", "com.societegenerale.failover"));

        @Test
        @DisplayName("should load a class exactly matching an allowlist entry")
        void exactMatchIsAllowed() {
            assertThat(restricted.<String>toClass("java.lang.String")).isEqualTo(String.class);
        }

        @Test
        @DisplayName("should load a class under an allowlisted package prefix")
        void packagePrefixMatchIsAllowed() {
            assertThat(restricted.<SamplePayload>toClass(SamplePayload.class.getName()))
                    .isEqualTo(SamplePayload.class);
        }

        @Test
        @DisplayName("should reject a class not covered by the allowlist")
        void unlistedClassIsRejected() {
            assertThatThrownBy(() -> restricted.toClass("java.lang.Runtime"))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining("java.lang.Runtime")
                    .hasMessageContaining("allowlist");
        }

        @Test
        @DisplayName("should not treat a package-prefix entry as a partial name match")
        void prefixDoesNotMatchPartialPackageName() {
            // "java.lang.String" is an exact entry; "java.lang.StringBuilder" must not pass via it
            assertThatThrownBy(() -> restricted.toClass("java.lang.StringBuilder"))
                    .isInstanceOf(FailoverStoreException.class);
        }

        @Test
        @DisplayName("should return null for null class name even when restricted")
        void nullClassNameReturnsNull() {
            assertThat(restricted.toClass(null)).isNull();
        }

        @Test
        @DisplayName("should allow everything when allowlist is empty")
        void emptyAllowlistAllowsAll() {
            JsonSerializer unrestricted = new JsonSerializer(OBJECT_MAPPER, List.of());
            assertThat(unrestricted.<Runtime>toClass("java.lang.Runtime")).isEqualTo(Runtime.class);
        }
    }

    @Nested
    @DisplayName("toClass(String) with a lazily-supplied allowlist")
    class ToClassWithSuppliedAllowlist {

        @Test
        @DisplayName("should resolve the allowlist from the supplier on first use")
        void resolvesFromSupplier() {
            JsonSerializer supplied = new JsonSerializer(OBJECT_MAPPER, () -> List.of("java.lang.String"));
            assertThat(supplied.<String>toClass("java.lang.String")).isEqualTo(String.class);
            assertThatThrownBy(() -> supplied.toClass("java.lang.Runtime"))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining("allowlist");
        }

        @Test
        @DisplayName("should invoke the supplier only once and memoize the result")
        void memoizesSupplier() {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            JsonSerializer supplied = new JsonSerializer(OBJECT_MAPPER, () -> {
                calls.incrementAndGet();
                return List.of("java.lang.String");
            });

            supplied.toClass("java.lang.String");
            supplied.toClass("java.lang.String");
            assertThatThrownBy(() -> supplied.toClass("java.lang.Runtime")).isInstanceOf(FailoverStoreException.class);

            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should allow everything when the supplier yields an empty list")
        void emptySuppliedAllowlistAllowsAll() {
            JsonSerializer supplied = new JsonSerializer(OBJECT_MAPPER, List::of);
            assertThat(supplied.<Runtime>toClass("java.lang.Runtime")).isEqualTo(Runtime.class);
        }

        @Test
        @DisplayName("should treat a null supplier result as allow-all (defensive)")
        void nullSuppliedAllowlistAllowsAll() {
            JsonSerializer supplied = new JsonSerializer(OBJECT_MAPPER, () -> null);
            assertThat(supplied.<Runtime>toClass("java.lang.Runtime")).isEqualTo(Runtime.class);
        }
    }

    @Nested
    @DisplayName("serialize/deserialize round-trip")
    class RoundTrip {

        @Test
        @DisplayName("should reconstruct a POJO via toClassName + serialize + toClass + deserialize")
        void fullRoundTripUsingClassName() {
            SamplePayload original = new SamplePayload("round-trip", 7);

            String className = serializer.toClassName(original);
            String json = serializer.serialize(original);
            Class<SamplePayload> clazz = serializer.toClass(className);
            SamplePayload result = serializer.deserialize(json, clazz);

            assertThat(result).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("allowlist scope — nested/generic field types are not gated (audit I-02)")
    class AllowlistScopeNestedTypes {

        // Allowlist contains ONLY the top-level payload type — NOT java.time, java.math, or java.util.
        private final JsonSerializer restricted =
                new JsonSerializer(OBJECT_MAPPER, List.of(NestedPayload.class.getName()));

        @Test
        @DisplayName("a payload whose nested fields live in non-allowlisted packages round-trips — the allowlist gates only the top-level PAYLOAD_CLASS")
        void nestedForeignAndGenericTypesAreNotGated() {
            NestedPayload original = new NestedPayload(
                    "acme", Instant.parse("2026-06-16T00:00:00Z"), new BigDecimal("12.34"), List.of("a", "b"));

            // toClass only checks the top-level class name; deserialize reconstructs the nested
            // Instant / BigDecimal / List<String> structurally via Jackson, with NO allowlist lookup.
            String className = restricted.toClassName(original);
            String json = restricted.serialize(original);
            Class<NestedPayload> clazz = restricted.toClass(className);
            NestedPayload result = restricted.deserialize(json, clazz);

            assertThat(result).isEqualTo(original);
        }

        @Test
        @DisplayName("the top-level class still must be allowlisted — a foreign top-level class is rejected")
        void topLevelClassIsStillGated() {
            assertThatThrownBy(() -> restricted.toClass("java.time.Instant"))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining("allowlist");
        }
    }
}