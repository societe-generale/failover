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
class StoreContextTest {

    @Mock private Failover failover;

    @Test
    @DisplayName("builder populates all fields correctly")
    void builderPopulatesAllFields() {
        List<Object> args = List.of("id-1");
        String payload = "my-payload";

        StoreContext<String> ctx = StoreContext.<String>builder()
                .failover(failover)
                .args(args)
                .payload(payload)
                .build();

        assertThat(ctx.getFailover()).isSameAs(failover);
        assertThat(ctx.getArgs()).isEqualTo(args);
        assertThat(ctx.getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("two contexts with same values are equal")
    void equalityBasedOnFieldValues() {
        List<Object> args = List.of("x");
        StoreContext<String> a = StoreContext.<String>builder().failover(failover).args(args).payload("v").build();
        StoreContext<String> b = StoreContext.<String>builder().failover(failover).args(args).payload("v").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("contexts with different payloads are not equal")
    void differentPayloadsNotEqual() {
        List<Object> args = List.of("x");
        StoreContext<String> a = StoreContext.<String>builder().failover(failover).args(args).payload("v1").build();
        StoreContext<String> b = StoreContext.<String>builder().failover(failover).args(args).payload("v2").build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("is immutable — no setter methods")
    void isImmutable() {
        Class<StoreContext> clazz = StoreContext.class;
        long setterCount = java.util.Arrays.stream(clazz.getMethods())
                .filter(m -> m.getName().startsWith("set"))
                .count();
        assertThat(setterCount).isZero();
    }
}