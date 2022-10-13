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

package com.societegenerale.failover.store;

import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Anand Manissery
 */
class TimeUnitConverterTest {

    @Test
    void shouldConvert() {
        assertThat(TimeUnitConverter.convert(ChronoUnit.DAYS)).isEqualTo(TimeUnit.DAYS);
        assertThat(TimeUnitConverter.convert(ChronoUnit.HOURS)).isEqualTo(TimeUnit.HOURS);
        assertThat(TimeUnitConverter.convert(ChronoUnit.MINUTES)).isEqualTo(TimeUnit.MINUTES);
        assertThat(TimeUnitConverter.convert(ChronoUnit.SECONDS)).isEqualTo(TimeUnit.SECONDS);
        assertThat(TimeUnitConverter.convert(ChronoUnit.MILLIS)).isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(TimeUnitConverter.convert(ChronoUnit.MICROS)).isEqualTo(TimeUnit.MICROSECONDS);
        assertThat(TimeUnitConverter.convert(ChronoUnit.NANOS)).isEqualTo(TimeUnit.NANOSECONDS);
    }

    @Test
    void shouldReturnNullWhenGivenUnitIsNull() {
        assertThat(TimeUnitConverter.convert(null)).isNull();
    }

    @Test
    void shouldThrowExceptionWhenUnitNotMatching() {
        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class, () -> TimeUnitConverter.convert(ChronoUnit.DECADES));
        assertThat(exception).isInstanceOf(UnsupportedOperationException.class);
        assertThat(exception.getMessage()).isEqualTo("Unsupported ChronoUnit : 'Decades' . Supported types are { DAYS, HOURS, MINUTES, SECONDS, MILLIS, MICROS, NANOS }");
    }
}