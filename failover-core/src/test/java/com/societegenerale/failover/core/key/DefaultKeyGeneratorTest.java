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

package com.societegenerale.failover.core.key;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.societegenerale.failover.annotations.Failover;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Integer.toHexString;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * @author Anand Manissery
 */
class DefaultKeyGeneratorTest {

    private static final Failover FAILOVER = mock(Failover.class);

    private final DefaultKeyGenerator defaultKeyProvider = new DefaultKeyGenerator();

    @Test
    @DisplayName("should return 'no-arg' when argument is null")
    void shouldReturnNoArgWhenArgumentIsNull() {
        String key = defaultKeyProvider.key(FAILOVER, null);
        assertThat(key).isEqualTo("NO-ARG");
    }

    @Test
    @DisplayName("should return 'no-arg' when argument is empty")
    void shouldReturnNoArgWhenArgumentIsEmpty() {
        String key = defaultKeyProvider.key(FAILOVER, new ArrayList<>());
        assertThat(key).isEqualTo("NO-ARG");
    }

    @Test
    @DisplayName("should return the key by concatenating all arguments")
    void shouldReturnTheKeyByConcatenatingAllArguments() {
        String key = defaultKeyProvider.key(FAILOVER, List.of("x","y",1L,2,3));
        assertThat(key).isEqualTo("x:y:1:2:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a collection")
    void shouldReturnTheKeyWhenOneArgIsACollection() {
        String key = defaultKeyProvider.key(FAILOVER, List.of("x", asList(1L,2,3), "y"));
        assertThat(key).isEqualTo("x:1,2,3:y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is an array")
    void shouldReturnTheKeyWhenOneArgIsAnArray() {
        String key = defaultKeyProvider.key(FAILOVER, List.of("x", new int[]{1,2,3}, "y"));
        assertThat(key).isEqualTo("x:1,2,3:y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a null value")
    void shouldReturnTheKeyWhenOneArgIsNull() {
        String key = defaultKeyProvider.key(FAILOVER, asList("x", null, "y"));
        assertThat(key).isEqualTo("x::y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a list with a null value")
    void shouldReturnTheKeyWhenOneArgIsNullInList() {
        String key = defaultKeyProvider.key(FAILOVER, List.of("x", asList(1L,null,3), "y"));
        assertThat(key).isEqualTo("x:1,,3:y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is an array with a null value")
    void shouldReturnTheKeyWhenOneArgIsNullInAnArray() {
        String key = defaultKeyProvider.key(FAILOVER, List.of("x", new String[]{"1",null,"3"}, "y"));
        assertThat(key).isEqualTo("x:1,,3:y");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a BigDecimal")
    void shouldReturnTheKeyWhenArgContainsBigDecimal() {
        String key = defaultKeyProvider.key(FAILOVER, List.of(1L, new BigDecimal(2), "3"));
        assertThat(key).isEqualTo("1:2:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a Boolean true")
    void shouldReturnTheKeyWhenArgContainsBooleanTrue() {
        String key = defaultKeyProvider.key(FAILOVER, List.of(1L, Boolean.TRUE, "3"));
        assertThat(key).isEqualTo("1:true:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a Boolean false")
    void shouldReturnTheKeyWhenArgContainsBooleanFalse() {
        String key = defaultKeyProvider.key(FAILOVER, List.of(1L, Boolean.FALSE, "3"));
        assertThat(key).isEqualTo("1:false:3");
    }

    @Test
    @DisplayName("should return the key when the argument is a String alone")
    void shouldReturnTheKeyWhenArgIsStringAlone() {
        String key = defaultKeyProvider.key(FAILOVER, List.of("hello"));
        assertThat(key).isEqualTo("hello");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a Double")
    void shouldReturnTheKeyWhenArgContainsDouble() {
        String key = defaultKeyProvider.key(FAILOVER, List.of(1L, 2.5, "3"));
        assertThat(key).isEqualTo("1:2.5:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a Float")
    void shouldReturnTheKeyWhenArgContainsFloat() {
        String key = defaultKeyProvider.key(FAILOVER, List.of(1L, 2.5f, "3"));
        assertThat(key).isEqualTo("1:2.5:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a Short")
    void shouldReturnTheKeyWhenArgContainsShort() {
        String key = defaultKeyProvider.key(FAILOVER, List.of(1L, (short) 7, "3"));
        assertThat(key).isEqualTo("1:7:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a Byte")
    void shouldReturnTheKeyWhenArgContainsByte() {
        String key = defaultKeyProvider.key(FAILOVER, List.of(1L, (byte) 9, "3"));
        assertThat(key).isEqualTo("1:9:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is a BigInteger")
    void shouldReturnTheKeyWhenArgContainsBigInteger() {
        String key = defaultKeyProvider.key(FAILOVER, List.of(1L, BigInteger.valueOf(42), "3"));
        assertThat(key).isEqualTo("1:42:3");
    }

    @Test
    @DisplayName("should return the key when one of the argument is an AtomicInteger (Number subtype)")
    void shouldReturnTheKeyWhenArgContainsAtomicInteger() {
        String key = defaultKeyProvider.key(FAILOVER, asList(1L, new AtomicInteger(5), "3"));
        assertThat(key).isEqualTo("1:5:3");
    }

    @Test
    @DisplayName("should return the key for a custom Number subtype via valueOf")
    void shouldReturnTheKeyForCustomNumberSubtype() {
        Number custom = new Number() {
            @Override public int intValue()    { return 99; }
            @Override public long longValue()  { return 99L; }
            @Override public float floatValue(){ return 99f; }
            @Override public double doubleValue(){ return 99.0; }
            @Override public String toString() { return "99"; }
        };
        String key = defaultKeyProvider.key(FAILOVER, List.of(custom));
        assertThat(key).isEqualTo("99");
    }

    @Test
    @DisplayName("should return the key when the arguments are of multiple types")
    void shouldReturnTheKeyWhenArgContainsMultipleTypes() {
        var one = 1L;
        Long two = 2L;
        var object = new Object();
        String key = defaultKeyProvider.key(FAILOVER, List.of(one, two, new BigDecimal(3), "4", object));
        assertThat(key).isEqualTo("1:2:3:4:java.lang.Object@%s".formatted(toHexString(object.hashCode())));
    }

    @Test
    @DisplayName("should return the key when the argument is of any non primitive Object")
    void shouldReturnTheKeyWhenArgContainsAnyObjectTypesOtherThanPrimitiveType() {
        var object = new Object();
        String key = defaultKeyProvider.key(FAILOVER, List.of(1, object, "3"));
        assertThat(key).isEqualTo("1:java.lang.Object@%s:3".formatted(toHexString(object.hashCode())));
    }

    @Test
    @DisplayName("should use toString() for a record arg (deterministic, JVM-restart-stable key)")
    void shouldUseToStringForRecordArg() {
        record Money(String currency, long amount) {}
        String key = defaultKeyProvider.key(FAILOVER, List.of(1, new Money("EUR", 42), "3"));
        assertThat(key).isEqualTo("1:Money[currency=EUR, amount=42]:3");
    }

    @Test
    @DisplayName("should produce equal keys for value-equal record args (identity hash would differ)")
    void shouldProduceEqualKeysForValueEqualRecords() {
        record Money(String currency, long amount) {}
        String key1 = defaultKeyProvider.key(FAILOVER, List.of(new Money("EUR", 42)));
        String key2 = defaultKeyProvider.key(FAILOVER, List.of(new Money("EUR", 42)));
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("should use toString() for an enum arg")
    void shouldUseToStringForEnumArg() {
        String key = defaultKeyProvider.key(FAILOVER, List.of(1, java.time.DayOfWeek.MONDAY, "3"));
        assertThat(key).isEqualTo("1:MONDAY:3");
    }

    @Test
    @DisplayName("should fall back to Class@hashCode for a type that does not override toString")
    void shouldFallBackToHashCodeWhenNoToStringOverride() {
        var object = new Object();   // identity toString()
        String key = defaultKeyProvider.key(FAILOVER, List.of(1, object, "3"));
        assertThat(key).isEqualTo("1:java.lang.Object@%s:3".formatted(toHexString(object.hashCode())));
    }

    @Test
    @DisplayName("should return different keys when one of the argument contains comma separated values with different order")
    void shouldReturnDifferentKeyWhenOneOfTheArgContainsComaSeparatedStringValues() {
        var ids1 = "2,3,4";
        var ids2 = "4,3,2";
        var ids3 = "2,4,3";
        String key1 = defaultKeyProvider.key(FAILOVER, List.of(1, ids1, "5"));
        String key2 = defaultKeyProvider.key(FAILOVER, List.of(1, ids2, "5"));
        String key3 = defaultKeyProvider.key(FAILOVER, List.of(1, ids3, "5"));
        assertThat(key1).isNotEqualTo(key2).isNotEqualTo(key3);
        assertThat(key1).isEqualTo("1:2,3,4:5");
        assertThat(key2).isEqualTo("1:4,3,2:5");
        assertThat(key3).isEqualTo("1:2,4,3:5");
    }

    /**
     * Asserts the warning-vs-no-warning branching in {@code castToStringValue}. The string a key arg
     * produces is identical whether it is routed through the known-type ({@code isOfType}) path, the
     * {@code overridesToString} path, or the identity-hash fallback — so only the emitted WARN log
     * distinguishes those branches. These tests pin that behaviour (and kill the corresponding
     * conditional/return mutants).
     */
    @Nested
    @DisplayName("warning on unstable key source")
    class UnstableKeySourceWarning {

        private Logger logger;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attachAppender() {
            logger = (Logger) LoggerFactory.getLogger(DefaultKeyGenerator.class);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
        }

        private boolean warnLogged() {
            return appender.list.stream()
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("overrides neither toString()"));
        }

        private long warnCount() {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("overrides neither toString()"))
                    .count();
        }

        @Test
        @DisplayName("warns when an arg overrides neither toString() nor is a known type (identity-hash fallback)")
        void warnsForIdentityHashFallback() {
            defaultKeyProvider.key(FAILOVER, List.of(new Object()));
            assertThat(warnLogged()).isTrue();
        }

        @Test
        @DisplayName("does not warn for a Number subtype, even one that does not override toString() (known type)")
        void doesNotWarnForNumberSubtype() {
            // A Number that does NOT override toString(): the known-type (isOfType) path must handle it
            // via valueOf without warning. If that path is bypassed it would fall to the warning branch.
            Number numberWithoutToString = new Number() {
                @Override public int intValue()      { return 7; }
                @Override public long longValue()     { return 7L; }
                @Override public float floatValue()   { return 7f; }
                @Override public double doubleValue()  { return 7.0; }
            };
            defaultKeyProvider.key(FAILOVER, List.of(numberWithoutToString));
            assertThat(warnLogged()).isFalse();
        }

        @Test
        @DisplayName("does not warn for a record arg (overridesToString path)")
        void doesNotWarnForRecord() {
            record Money(String currency, long amount) {}
            defaultKeyProvider.key(FAILOVER, List.of(new Money("EUR", 42)));
            assertThat(warnLogged()).isFalse();
        }

        @Test
        @DisplayName("warns only once per type across repeated calls (hot-path log throttling)")
        void warnsOnlyOncePerType() {
            for (int i = 0; i < 5; i++) {
                defaultKeyProvider.key(FAILOVER, List.of(new Object()));
            }
            assertThat(warnCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("warns once per distinct unstable type")
        void warnsPerDistinctType() {
            class FirstUnstable { }   // identity toString()
            class SecondUnstable { }  // identity toString()
            defaultKeyProvider.key(FAILOVER, List.of(new FirstUnstable()));
            defaultKeyProvider.key(FAILOVER, List.of(new FirstUnstable()));
            defaultKeyProvider.key(FAILOVER, List.of(new SecondUnstable()));
            assertThat(warnCount()).isEqualTo(2);
        }
    }
}