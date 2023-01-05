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

package com.societegenerale.failover.core.scanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.societegenerale.failover.core.scanner.ReflectionsExceptionHandler.REFLECTION_ERR_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Anand Manissery
 */
class ReflectionsExceptionHandlerTest {

    private ReflectionsExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new ReflectionsExceptionHandler();
    }

    @Test
    void wrapAnyRuntimeExceptionWithFailoverScannerException() {
        FailoverScannerException exception = assertThrows(FailoverScannerException.class,
                () -> exceptionHandler.execute(()-> { throw new DummyException("Dummy Exception"); }));
        assertThat(exception).isInstanceOf(FailoverScannerException.class);
        assertThat(exception.getMessage()).isEqualTo(REFLECTION_ERR_MESSAGE);
    }

    static class DummyException extends RuntimeException {
        public DummyException(String message) {
            super(message);
        }
    }
}