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

package com.societegenerale.failover.scanner;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.scanner.FailoverScannerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Anand Manissery
 */
class SpringContextFailoverScannerTest {

    private SpringContextFailoverScanner scanner;
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        scanner = new SpringContextFailoverScanner();
        scanner.setApplicationContext(applicationContext);
    }

    @Test
    @DisplayName("should discover @Failover on concrete class methods")
    void shouldDiscoverFailoverOnConcreteMethods() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"referentialBean"});
        doReturn(ConcreteReferential.class).when(applicationContext).getType("referentialBean");

        scanner.afterSingletonsInstantiated();

        List<Failover> all = scanner.findAllFailover();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(Failover::name).containsExactlyInAnyOrder("find-by-id", "find-all");
    }

    @Test
    @DisplayName("should discover @Failover placed on interface methods (Spring annotation bridge)")
    void shouldDiscoverFailoverOnInterfaceMethods() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"impl"});
        doReturn(ReferentialImpl.class).when(applicationContext).getType("impl");

        scanner.afterSingletonsInstantiated();

        List<Failover> all = scanner.findAllFailover();
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().name()).isEqualTo("interface-method");
    }

    @Test
    @DisplayName("should return null when name not found")
    void shouldReturnNullForMissingName() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});
        scanner.afterSingletonsInstantiated();
        assertThat(scanner.findFailoverByName("missing")).isNull();
    }

    @Test
    @DisplayName("should return failover by name")
    void shouldReturnFailoverByName() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"bean"});
        doReturn(ConcreteReferential.class).when(applicationContext).getType("bean");

        scanner.afterSingletonsInstantiated();

        Failover result = scanner.findFailoverByName("find-by-id");
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("find-by-id");
        assertThat(result.expiryDuration()).isEqualTo(1);
        assertThat(result.expiryUnit()).isEqualTo(ChronoUnit.HOURS);
    }

    @Test
    @DisplayName("should throw FailoverScannerException on duplicate @Failover names")
    void shouldThrowOnDuplicateNames() {
        when(applicationContext.getBeanDefinitionNames())
            .thenReturn(new String[]{"bean1", "bean2"});
        doReturn(DuplicateA.class).when(applicationContext).getType("bean1");
        doReturn(DuplicateB.class).when(applicationContext).getType("bean2");

        assertThatThrownBy(() -> scanner.afterSingletonsInstantiated())
            .isInstanceOf(FailoverScannerException.class)
            .hasMessageContaining("Duplicate @Failover name")
            .hasMessageContaining("duplicate-name");
    }

    @Test
    @DisplayName("should return empty list when no @Failover found")
    void shouldReturnEmptyWhenNoFailovers() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"plainBean"});
        doReturn(PlainBean.class).when(applicationContext).getType("plainBean");

        scanner.afterSingletonsInstantiated();

        assertThat(scanner.findAllFailover()).isEmpty();
    }

    @Test
    @DisplayName("should skip beans whose type cannot be determined")
    void shouldSkipBeansWithUndeterminableType() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"problem", "good"});
        when(applicationContext.getType("problem")).thenThrow(new RuntimeException("type error"));
        doReturn(ConcreteReferential.class).when(applicationContext).getType("good");

        scanner.afterSingletonsInstantiated();

        assertThat(scanner.findAllFailover()).hasSize(2);
    }

    @Test
    @DisplayName("findAllFailover returns empty list before scan runs")
    void shouldReturnEmptyBeforeScan() {
        assertThat(scanner.findAllFailover()).isEmpty();
    }

    // ── findAllPayloadTypes ─────────────────────────────────────────────────────

    @Test
    @DisplayName("payload types include the return type and the element type of a collection return")
    void shouldCollectReturnAndCollectionElementTypes() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"referentialBean"});
        doReturn(ConcreteReferential.class).when(applicationContext).getType("referentialBean");

        scanner.afterSingletonsInstantiated();

        // findById → String ; findAll → element of List<String> = String
        assertThat(scanner.findAllPayloadTypes()).containsExactly(String.class);
    }

    @Test
    @DisplayName("payload types resolve POJO returns and array component types; void is ignored")
    void shouldCollectPojoAndArrayComponentTypesAndIgnoreVoid() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"varied"});
        doReturn(VariedReturns.class).when(applicationContext).getType("varied");

        scanner.afterSingletonsInstantiated();

        assertThat(scanner.findAllPayloadTypes()).containsExactlyInAnyOrder(SamplePojo.class);
    }

    @Test
    @DisplayName("findAllPayloadTypes returns empty set before scan runs")
    void payloadTypesEmptyBeforeScan() {
        assertThat(scanner.findAllPayloadTypes()).isEmpty();
    }

    @Test
    @DisplayName("payload-type resolution skips Void, raw and non-Class collection elements; domained failover passes the mismatch filter")
    void shouldHandleEdgeReturnTypes() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"edge"});
        doReturn(EdgeReturns.class).when(applicationContext).getType("edge");

        scanner.afterSingletonsInstantiated();

        // boxedVoid → skipped (Void); rawList/nested/bi → element unresolvable, skipped; domained → String
        assertThat(scanner.findAllPayloadTypes()).containsExactly(String.class);
        assertThat(scanner.findAllFailover()).extracting(Failover::name)
                .contains("boxed-void", "raw-collection", "nested-generic", "bi-collection", "domained");
    }

    // ── Advisability warnings (audit A8) ────────────────────────────────────────

    @org.junit.jupiter.api.Nested
    @DisplayName("warns when @Failover cannot be advised by the proxy")
    class AdvisabilityWarnings {

        private ch.qos.logback.classic.Logger logger;
        private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;

        @BeforeEach
        void attach() {
            logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SpringContextFailoverScanner.class);
            appender = new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @org.junit.jupiter.api.AfterEach
        void detach() {
            logger.detachAppender(appender);
        }

        private List<String> warnings() {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        private void scan(Class<?> type) {
            when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"bean"});
            doReturn(type).when(applicationContext).getType("bean");
            scanner.afterSingletonsInstantiated();
        }

        @Test
        @DisplayName("interface-only annotation warns it is not on the concrete method")
        void interfaceOnlyWarns() {
            scan(ReferentialImpl.class);
            assertThat(warnings()).anySatisfy(m -> assertThat(m)
                    .contains("interface-method").contains("NOT be applied").contains("supertype/interface"));
        }

        @Test
        @DisplayName("final method warns")
        void finalMethodWarns() {
            scan(FinalMethodReferential.class);
            assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("final-method").contains("final"));
        }

        @Test
        @DisplayName("static method warns")
        void staticMethodWarns() {
            scan(StaticMethodReferential.class);
            assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("static-method").contains("static"));
        }

        @Test
        @DisplayName("non-public method warns")
        void nonPublicMethodWarns() {
            scan(NonPublicReferential.class);
            assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("protected-method").contains("not public"));
        }

        @Test
        @DisplayName("final class warns")
        void finalClassWarns() {
            scan(FinalReferential.class);
            assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("final-class").contains("is final"));
        }

        @Test
        @DisplayName("a well-placed public method on a non-final class does NOT warn")
        void wellPlacedDoesNotWarn() {
            scan(ConcreteReferential.class);
            assertThat(warnings()).noneMatch(m -> m.contains("NOT be applied"));
        }

        @Test
        @DisplayName("an interface bean (Feign/Spring-Data/@HttpExchange-like) with @Failover on the interface method does NOT warn")
        void interfaceBeanDoesNotWarn() {
            // bean type IS the interface (JDK dynamic proxy) — annotation on the interface method is correct
            scan(ReferentialInterface.class);
            assertThat(scanner.findFailoverByName("interface-method")).isNotNull();
            assertThat(warnings()).noneMatch(m -> m.contains("NOT be applied"));
        }

        @Test
        @DisplayName("the suggested fix — impl overrides AND annotates the method — silences the warning")
        void overrideAnnotatedImplDoesNotWarn() {
            scan(OverrideAnnotatedImpl.class);
            assertThat(scanner.findFailoverByName("override-method")).isNotNull();
            assertThat(warnings()).noneMatch(m -> m.contains("NOT be applied"));
        }

        @Test
        @DisplayName("interface-only suggestion names the concrete method to override + annotate")
        void interfaceOnlySuggestsOverrideAndAnnotate() {
            scan(ReferentialImpl.class);
            assertThat(warnings()).anySatisfy(m -> assertThat(m)
                    .contains("override 'findByCode'")
                    .contains("ReferentialImpl"));
        }

        @Test
        @DisplayName("multiple defects are reported together in a single warning")
        void multipleReasonsCombined() {
            scan(StaticFinalReferential.class);
            assertThat(warnings()).anySatisfy(m -> assertThat(m)
                    .contains("static-final-method")
                    .contains("static")
                    .contains("final"));
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static class ConcreteReferential {
        @Failover(name = "find-by-id")
        public String findById(Long id) { return null; }

        @Failover(name = "find-all")
        public List<String> findAll() { return null; }
    }

    static class SamplePojo { }

    static class VariedReturns {
        @Failover(name = "pojo")
        public SamplePojo pojo() { return null; }

        @Failover(name = "array")
        public SamplePojo[] array() { return null; }

        @Failover(name = "voidish")
        public void doNothing() { /*for test only*/ }
    }

    interface ReferentialInterface {
        @Failover(name = "interface-method")
        String findByCode(String code);
    }

    static class ReferentialImpl implements ReferentialInterface {
        @Override
        public String findByCode(String code) { return null; }
    }

    static class DuplicateA {
        @Failover(name = "duplicate-name")
        public String methodA() { return null; }
    }

    static class DuplicateB {
        @Failover(name = "duplicate-name")
        public String methodB() { return null; }
    }

    static class PlainBean {
        public String noAnnotation() { return null; }
    }

    static class FinalMethodReferential {
        @Failover(name = "final-method")
        public final String findFinal() { return null; }
    }

    static class StaticMethodReferential {
        @Failover(name = "static-method")
        public static String findStatic() { return null; }
    }

    static class NonPublicReferential {
        @Failover(name = "protected-method")
        protected String findProtected() { return null; }
    }

    static final class FinalReferential {
        @Failover(name = "final-class")
        public String find() { return null; }
    }

    /** Impl that overrides the interface method AND annotates the override — the recommended fix. */
    static class OverrideAnnotatedImpl implements ReferentialInterface {
        @Override
        @Failover(name = "override-method")
        public String findByCode(String code) { return null; }
    }

    static class StaticFinalReferential {
        @Failover(name = "static-final-method")
        public static final String findStaticFinal() { return null; }
    }

    /** Collection with two type parameters — exercises the {@code args.length == 1} guard. */
    interface BiCollection<A, B> extends Collection<A> { }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static class EdgeReturns {
        @Failover(name = "boxed-void")
        public Void boxedVoid() { return null; }                    // returnType == Void.class

        @Failover(name = "raw-collection")
        public List rawList() { return null; }                      // generic return is not a ParameterizedType

        @Failover(name = "nested-generic")
        public List<List<String>> nested() { return null; }         // element arg is not a Class

        @Failover(name = "bi-collection")
        public BiCollection<String, Integer> bi() { return null; }  // two type args → length != 1

        @Failover(name = "domained", domain = "country")
        public String domained() { return null; }                   // non-blank domain → passes the filter
    }
}
