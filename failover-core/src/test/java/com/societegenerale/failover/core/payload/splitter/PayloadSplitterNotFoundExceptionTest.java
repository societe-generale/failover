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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadSplitterNotFoundExceptionTest {

    @Test
    @DisplayName("message is preserved")
    void messageIsPreserved() {
        var ex = new PayloadSplitterNotFoundException("splitter 'foo' not found");
        assertThat(ex.getMessage()).isEqualTo("splitter 'foo' not found");
    }

    @Test
    @DisplayName("is a RuntimeException — unchecked")
    void isRuntimeException() {
        assertThat(new PayloadSplitterNotFoundException("x"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("thrown and caught without checked-exception declaration")
    void thrownAndCaughtWithoutCheckedDeclaration() {
        assertThatThrownBy(() -> { throw new PayloadSplitterNotFoundException("boom"); })
                .isInstanceOf(PayloadSplitterNotFoundException.class)
                .hasMessage("boom");
    }
}