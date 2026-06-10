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

package com.societegenerale.failover.core.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class FailoverStoreExceptionTest {

    @Test
    @DisplayName("should create exception with the given message")
    void shouldCreateExceptionWithMessage() {
        var exception = new FailoverStoreException("store operation failed");

        assertThat(exception.getMessage()).isEqualTo("store operation failed");
    }

    @Test
    @DisplayName("should create exception with message and cause")
    void shouldCreateExceptionWithMessageAndCause() {
        var cause = new RuntimeException("underlying error");
        var exception = new FailoverStoreException("store operation failed", cause);

        assertThat(exception.getMessage()).isEqualTo("store operation failed");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("should be a RuntimeException")
    void shouldBeARuntimeException() {
        assertThat(new FailoverStoreException("error")).isInstanceOf(RuntimeException.class);
    }
}
