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

package com.societegenerale.failover.core.payload.splitter;

import com.societegenerale.failover.annotations.Failover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RecoverContextTest {

    @Mock private Failover failover;
    @Mock private Throwable cause;

    @Test
    @DisplayName("builder populates all fields correctly")
    void builderPopulatesAllFields() {
        List<Object> args = List.of("id-1");

        RecoverContext<String> ctx = RecoverContext.<String>builder()
                .failover(failover)
                .args(args)
                .clazz(String.class)
                .cause(cause)
                .payload("recovered")
                .build();

        assertThat(ctx.getFailover()).isSameAs(failover);
        assertThat(ctx.getArgs()).isEqualTo(args);
        assertThat(ctx.getClazz()).isEqualTo(String.class);
        assertThat(ctx.getCause()).isSameAs(cause);
        assertThat(ctx.getPayload()).isEqualTo("recovered");
    }

    @Test
    @DisplayName("payload starts null when not set in builder")
    void payloadStartsNullWhenNotSet() {
        RecoverContext<String> ctx = RecoverContext.<String>builder()
                .failover(failover).args(List.of()).clazz(String.class).cause(cause)
                .build();

        assertThat(ctx.getPayload()).isNull();
    }

    @Test
    @DisplayName("setPayload mutates payload — context is mutable by design")
    void setPayloadMutatesPayload() {
        RecoverContext<String> ctx = RecoverContext.<String>builder()
                .failover(failover).args(List.of()).clazz(String.class).cause(cause)
                .build();

        ctx.setPayload("set-after-build");

        assertThat(ctx.getPayload()).isEqualTo("set-after-build");
    }

    @Test
    @DisplayName("two contexts with same values are equal")
    void equalityBasedOnFieldValues() {
        var args = List.<Object>of("x");
        RecoverContext<String> a = RecoverContext.<String>builder()
                .failover(failover).args(args).clazz(String.class).cause(cause).payload("v").build();
        RecoverContext<String> b = RecoverContext.<String>builder()
                .failover(failover).args(args).clazz(String.class).cause(cause).payload("v").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("contexts with different payloads are not equal")
    void differentPayloadsNotEqual() {
        var args = List.<Object>of("x");
        RecoverContext<String> a = RecoverContext.<String>builder()
                .failover(failover).args(args).clazz(String.class).cause(cause).payload("v1").build();
        RecoverContext<String> b = RecoverContext.<String>builder()
                .failover(failover).args(args).clazz(String.class).cause(cause).payload("v2").build();

        assertThat(a).isNotEqualTo(b);
    }
}