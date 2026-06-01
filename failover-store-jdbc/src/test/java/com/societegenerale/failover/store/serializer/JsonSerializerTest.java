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

package com.societegenerale.failover.store.serializer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
        @DisplayName("should throw when class name does not exist on classpath")
        void unknownClassNameThrows() {
            assertThatThrownBy(() -> serializer.toClass("com.example.NonExistentClass"))
                    .hasMessage("com.example.NonExistentClass")
                    .isInstanceOf(ClassNotFoundException.class);
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
}